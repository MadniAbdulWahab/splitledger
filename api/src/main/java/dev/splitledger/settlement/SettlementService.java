package dev.splitledger.settlement;

import dev.splitledger.common.BusinessRuleException;
import dev.splitledger.common.ConflictException;
import dev.splitledger.common.IdempotentCreator;
import dev.splitledger.common.NotFoundException;
import dev.splitledger.common.Sha256;
import dev.splitledger.group.GroupDtos.MemberResponse;
import dev.splitledger.group.GroupService;
import dev.splitledger.ledger.CorrectionLog;
import dev.splitledger.ledger.EntryKind;
import dev.splitledger.ledger.LedgerService;
import dev.splitledger.ledger.Posting;
import dev.splitledger.outbox.LedgerEvent.EventType;
import dev.splitledger.outbox.OutboxService;
import dev.splitledger.settlement.SettlementDtos.DebtsResponse;
import dev.splitledger.settlement.SettlementDtos.PaymentResponse;
import dev.splitledger.settlement.SettlementDtos.RecordPaymentRequest;
import dev.splitledger.settlement.SettlementDtos.SettlementResponse;
import dev.splitledger.settlement.SettlementDtos.SuggestedPayment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SettlementService {

    static final String IDEMPOTENCY_CONSTRAINT = "payment_idempotency_key_unique";
    /** The friends live in Germany; a payment at 00:30 belongs to that day, not to the day before in UTC. */
    static final ZoneId GROUP_TIME_ZONE = ZoneId.of("Europe/Berlin");

    private final PaymentRepository payments;
    private final GroupService groupService;
    private final LedgerService ledgerService;
    private final IdempotentCreator idempotentCreator;
    private final OutboxService outbox;
    private final CorrectionLog corrections;
    private final JdbcTemplate jdbc;

    SettlementService(PaymentRepository payments, GroupService groupService, LedgerService ledgerService,
                      IdempotentCreator idempotentCreator, OutboxService outbox, CorrectionLog corrections,
                      JdbcTemplate jdbc) {
        this.payments = payments;
        this.groupService = groupService;
        this.ledgerService = ledgerService;
        this.idempotentCreator = idempotentCreator;
        this.outbox = outbox;
        this.corrections = corrections;
        this.jdbc = jdbc;
    }

    /**
     * Who owes whom, pair by pair: every participant owes the payer their share of each expense,
     * payments reduce that, reversed expenses do not count, and opposite debts between two people
     * cancel out. For one member, the debts add up to exactly their balance in the ledger.
     *
     * <p>This is what people think in ("I owe Lucy 10, Tim owes me 5"). {@link #suggest} answers a
     * different question: the fewest transfers that settle the whole group.
     */
    @Transactional(readOnly = true)
    public DebtsResponse debts(long groupId) {
        Map<Long, MemberResponse> members = membersById(groupId);
        List<SuggestedPayment> debts = jdbc.query("""
                with claims as (
                    -- each participant owes the payer their share
                    select s.member_id as debtor, e.payer_id as creditor, s.amount_cents as cents
                    from expense e
                    join expense_share s on s.expense_id = e.id
                    where e.group_id = ? and s.member_id <> e.payer_id
                      and not exists (
                          select 1 from ledger_entry original
                          join ledger_entry reversal on reversal.reference_id = original.id and reversal.kind = 'REVERSAL'
                          where original.kind = 'EXPENSE' and original.reference_id = e.id)
                    union all
                    -- a payment reduces what the payer owes the receiver, unless it was undone
                    select p.from_member_id, p.to_member_id, -p.amount_cents
                    from payment p
                    where p.group_id = ?
                      and not exists (
                          select 1 from ledger_entry original
                          join ledger_entry reversal on reversal.reference_id = original.id and reversal.kind = 'REVERSAL'
                          where original.kind = 'PAYMENT' and original.reference_id = p.id)
                ), per_pair as (
                    -- one row per pair (lower id first), signed: positive means "low owes high"
                    select least(debtor, creditor) as low, greatest(debtor, creditor) as high,
                           sum(case when debtor < creditor then cents else -cents end) as net
                    from claims
                    group by least(debtor, creditor), greatest(debtor, creditor)
                )
                select case when net > 0 then low else high end as debtor,
                       case when net > 0 then high else low end as creditor,
                       abs(net) as cents
                from per_pair
                where net <> 0
                order by cents desc, debtor, creditor
                """,
                (rs, row) -> transfer(members, rs.getLong("debtor"), rs.getLong("creditor"), rs.getLong("cents")),
                groupId, groupId);
        return new DebtsResponse(groupId, debts);
    }

    /** The fewest transfers that settle the whole group (greedy, at most n-1; see SettlementPlanner). */
    @Transactional(readOnly = true)
    public SettlementResponse suggest(long groupId) {
        Map<Long, MemberResponse> members = membersById(groupId);
        List<SuggestedPayment> suggestions = SettlementPlanner.plan(ledgerService.balances(groupId)).stream()
                .map(t -> transfer(members, t.fromMemberId(), t.toMemberId(), t.amountCents()))
                .toList();
        return new SettlementResponse(groupId, suggestions);
    }

    /**
     * GiroCode PNG for a transfer between two members, e.g. with "SplitLedger settle-up WG
     * Kessenich Oct 2026" as transfer text. Without an amount, the open debt between the two (or,
     * failing that, the suggested transfer) is used.
     */
    @Transactional(readOnly = true)
    public byte[] giroCode(long groupId, long fromMemberId, long toMemberId, Long amountCents) {
        Map<Long, MemberResponse> members = membersById(groupId);
        if (!members.containsKey(fromMemberId) || !members.containsKey(toMemberId) || fromMemberId == toMemberId) {
            throw new BusinessRuleException("A transfer needs two different members of group " + groupId);
        }
        long cents = amountCents != null ? amountCents
                : Stream.concat(debts(groupId).debts().stream(), suggest(groupId).payments().stream())
                .filter(p -> p.fromMemberId() == fromMemberId && p.toMemberId() == toMemberId)
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "Member " + fromMemberId + " does not owe member " + toMemberId + " anything"))
                .amountCents();
        SuggestedPayment payment = transfer(members, fromMemberId, toMemberId, cents);
        if (payment.toIban() == null) {
            throw new BusinessRuleException(payment.toName() + " has not entered an IBAN in this group yet");
        }
        String month = YearMonth.now(GROUP_TIME_ZONE).format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH));
        String text = "SplitLedger settle-up " + groupService.getGroup(groupId).name() + " " + month;
        return GiroCode.png(GiroCode.payload(payment.toName(), payment.toIban(), payment.toBic(), payment.amountCents(), text));
    }

    /**
     * Records that a payment happened. Ledger: +amount for the payer (their debt shrinks), -amount
     * for the receiver (their claim shrinks). Idempotent per key, like expenses.
     */
    public IdempotentCreator.Result<PaymentResponse> record(long groupId, String idempotencyKey, RecordPaymentRequest request,
                                                            long createdBy) {
        String requestHash = Sha256.hex(request.canonicalForm());
        return idempotentCreator.create(IDEMPOTENCY_CONSTRAINT,
                () -> findEarlier(groupId, idempotencyKey, requestHash),
                () -> insert(groupId, idempotencyKey, requestHash, request, createdBy));
    }

    private Map<Long, MemberResponse> membersById(long groupId) {
        return groupService.listMembers(groupId).stream()
                .collect(Collectors.toMap(MemberResponse::id, Function.identity()));
    }

    private static SuggestedPayment transfer(Map<Long, MemberResponse> members, long fromId, long toId, long cents) {
        MemberResponse from = members.get(fromId);
        MemberResponse to = members.get(toId);
        return new SuggestedPayment(from.id(), from.displayName(), to.id(), to.displayName(), to.iban(), to.bic(), cents);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> list(long groupId) {
        Map<Long, CorrectionLog.Entry> undone = corrections.undonePayments(groupId);
        return payments.findByGroupIdOrderByIdDesc(groupId).stream()
                .map(p -> PaymentResponse.from(p, undone.get(p.getId())))
                .toList();
    }

    /**
     * Undoes a payment recorded by mistake: reversing ledger entries cancel it, the payment row
     * stays, and the correction log records who undid it. At most once (409 afterwards).
     */
    @Transactional
    public PaymentResponse undo(long groupId, long paymentId, long memberId) {
        Payment payment = payments.findByIdAndGroupId(paymentId, groupId)
                .orElseThrow(() -> new NotFoundException("Payment " + paymentId + " not found in group " + groupId));
        List<Posting> reversal = ledgerService.reverse(groupId, EntryKind.PAYMENT, paymentId).stream()
                .map(entry -> new Posting(entry.getMemberId(), entry.getAmountCents()))
                .toList();
        // Same date as the payment's own event, so the statement worker books it in the same month.
        outbox.record(EventType.PAYMENT_REVERSED, groupId, paymentId,
                LocalDate.ofInstant(payment.getCreatedAt(), GROUP_TIME_ZONE),
                payment.getNote() != null ? payment.getNote() : "Settle-up payment", payment.getAmountCents(), reversal);
        corrections.paymentUndone(groupId, paymentId, memberId);
        return PaymentResponse.from(payment, corrections.undonePayments(groupId).get(paymentId));
    }

    private Optional<PaymentResponse> findEarlier(long groupId, String idempotencyKey, String requestHash) {
        return payments.findByGroupIdAndIdempotencyKey(groupId, idempotencyKey).map(payment -> {
            if (!payment.getRequestHash().equals(requestHash)) {
                throw new ConflictException(
                        "Idempotency-Key '" + idempotencyKey + "' was already used with a different request body");
            }
            return PaymentResponse.from(payment, corrections.undonePayments(groupId).get(payment.getId()));
        });
    }

    private PaymentResponse insert(long groupId, String idempotencyKey, String requestHash, RecordPaymentRequest request,
                                   long createdBy) {
        if (request.fromMemberId().equals(request.toMemberId())) {
            throw new BusinessRuleException("A payment needs two different members");
        }
        Set<Long> memberIds = groupService.memberIds(groupId);
        if (!memberIds.contains(request.fromMemberId()) || !memberIds.contains(request.toMemberId())) {
            throw new BusinessRuleException("Both members must belong to group " + groupId);
        }
        Payment payment = payments.save(new Payment(groupId, request.fromMemberId(), request.toMemberId(),
                request.amountCents(), request.note(), idempotencyKey, requestHash, createdBy));
        List<Posting> postings = List.of(
                new Posting(request.fromMemberId(), request.amountCents()),
                new Posting(request.toMemberId(), -request.amountCents()));
        ledgerService.post(groupId, EntryKind.PAYMENT, payment.getId(), postings);
        outbox.record(EventType.PAYMENT_RECORDED, groupId, payment.getId(),
                LocalDate.ofInstant(payment.getCreatedAt(), GROUP_TIME_ZONE),
                request.note() != null ? request.note() : "Settle-up payment", request.amountCents(), postings);
        return PaymentResponse.from(payment, null);
    }
}
