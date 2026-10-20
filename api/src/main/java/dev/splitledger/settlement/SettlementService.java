package dev.splitledger.settlement;

import dev.splitledger.common.BusinessRuleException;
import dev.splitledger.common.ConflictException;
import dev.splitledger.common.IdempotentCreator;
import dev.splitledger.common.Sha256;
import dev.splitledger.group.GroupDtos.MemberResponse;
import dev.splitledger.group.GroupService;
import dev.splitledger.ledger.EntryKind;
import dev.splitledger.ledger.LedgerService;
import dev.splitledger.ledger.Posting;
import dev.splitledger.settlement.SettlementDtos.PaymentResponse;
import dev.splitledger.settlement.SettlementDtos.RecordPaymentRequest;
import dev.splitledger.settlement.SettlementDtos.SettlementResponse;
import dev.splitledger.settlement.SettlementDtos.SuggestedPayment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SettlementService {

    static final String IDEMPOTENCY_CONSTRAINT = "payment_idempotency_key_unique";

    private final PaymentRepository payments;
    private final GroupService groupService;
    private final LedgerService ledgerService;
    private final IdempotentCreator idempotentCreator;

    SettlementService(PaymentRepository payments, GroupService groupService, LedgerService ledgerService,
                      IdempotentCreator idempotentCreator) {
        this.payments = payments;
        this.groupService = groupService;
        this.ledgerService = ledgerService;
        this.idempotentCreator = idempotentCreator;
    }

    @Transactional(readOnly = true)
    public SettlementResponse suggest(long groupId) {
        Map<Long, MemberResponse> members = groupService.listMembers(groupId).stream()
                .collect(Collectors.toMap(MemberResponse::id, Function.identity()));
        List<SuggestedPayment> suggestions = SettlementPlanner.plan(ledgerService.balances(groupId)).stream()
                .map(t -> {
                    MemberResponse from = members.get(t.fromMemberId());
                    MemberResponse to = members.get(t.toMemberId());
                    return new SuggestedPayment(from.id(), from.displayName(), to.id(), to.displayName(),
                            to.iban(), to.bic(), t.amountCents());
                })
                .toList();
        return new SettlementResponse(groupId, suggestions);
    }

    /**
     * Records that a payment happened. Ledger: +amount for the payer (their debt shrinks), -amount
     * for the receiver (their claim shrinks). Idempotent per key, like expenses.
     */
    public IdempotentCreator.Result<PaymentResponse> record(long groupId, String idempotencyKey, RecordPaymentRequest request) {
        String requestHash = Sha256.hex(request.canonicalForm());
        return idempotentCreator.create(IDEMPOTENCY_CONSTRAINT,
                () -> findEarlier(groupId, idempotencyKey, requestHash),
                () -> insert(groupId, idempotencyKey, requestHash, request));
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> list(long groupId) {
        return payments.findByGroupIdOrderByIdDesc(groupId).stream().map(PaymentResponse::from).toList();
    }

    private Optional<PaymentResponse> findEarlier(long groupId, String idempotencyKey, String requestHash) {
        return payments.findByGroupIdAndIdempotencyKey(groupId, idempotencyKey).map(payment -> {
            if (!payment.getRequestHash().equals(requestHash)) {
                throw new ConflictException(
                        "Idempotency-Key '" + idempotencyKey + "' was already used with a different request body");
            }
            return PaymentResponse.from(payment);
        });
    }

    private PaymentResponse insert(long groupId, String idempotencyKey, String requestHash, RecordPaymentRequest request) {
        if (request.fromMemberId().equals(request.toMemberId())) {
            throw new BusinessRuleException("A payment needs two different members");
        }
        Set<Long> memberIds = groupService.memberIds(groupId);
        if (!memberIds.contains(request.fromMemberId()) || !memberIds.contains(request.toMemberId())) {
            throw new BusinessRuleException("Both members must belong to group " + groupId);
        }
        Payment payment = payments.save(new Payment(groupId, request.fromMemberId(), request.toMemberId(),
                request.amountCents(), request.note(), idempotencyKey, requestHash));
        ledgerService.post(groupId, EntryKind.PAYMENT, payment.getId(), List.of(
                new Posting(request.fromMemberId(), request.amountCents()),
                new Posting(request.toMemberId(), -request.amountCents())));
        return PaymentResponse.from(payment);
    }
}
