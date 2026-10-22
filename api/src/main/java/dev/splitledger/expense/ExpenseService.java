package dev.splitledger.expense;

import dev.splitledger.common.BusinessRuleException;
import dev.splitledger.common.ConflictException;
import dev.splitledger.common.IdempotentCreator;
import dev.splitledger.common.NotFoundException;
import dev.splitledger.common.Sha256;
import dev.splitledger.expense.ExpenseDtos.CreateExpenseRequest;
import dev.splitledger.expense.ExpenseDtos.ExpenseResponse;
import dev.splitledger.expense.SplitCalculator.Allocation;
import dev.splitledger.expense.SplitCalculator.Participant;
import dev.splitledger.group.GroupService;
import dev.splitledger.ledger.EntryKind;
import dev.splitledger.ledger.LedgerService;
import dev.splitledger.ledger.Posting;
import dev.splitledger.outbox.LedgerEvent.EventType;
import dev.splitledger.outbox.OutboxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class ExpenseService {

    static final String IDEMPOTENCY_CONSTRAINT = "expense_idempotency_key_unique";

    private final ExpenseRepository expenses;
    private final GroupService groupService;
    private final LedgerService ledgerService;
    private final IdempotentCreator idempotentCreator;
    private final OutboxService outbox;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    ExpenseService(ExpenseRepository expenses, GroupService groupService, LedgerService ledgerService,
                   IdempotentCreator idempotentCreator, OutboxService outbox,
                   PlatformTransactionManager transactionManager) {
        this.expenses = expenses;
        this.groupService = groupService;
        this.ledgerService = ledgerService;
        this.idempotentCreator = idempotentCreator;
        this.outbox = outbox;
        this.writeTx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    public record Creation(ExpenseResponse expense, boolean replayed) {
    }

    /** Creates an expense and its ledger entries exactly once per (group, idempotency key). */
    public Creation create(long groupId, String idempotencyKey, CreateExpenseRequest request) {
        String requestHash = Sha256.hex(request.canonicalForm());
        return idempotentCreator.create(IDEMPOTENCY_CONSTRAINT,
                () -> findEarlier(groupId, idempotencyKey, requestHash),
                () -> insert(groupId, idempotencyKey, requestHash, request, null)).value();
    }

    /**
     * Creates an expense inside the caller's transaction (used when a bank transaction is
     * confirmed: the expense and the status change must commit together). The caller holds a lock
     * that prevents a second attempt with the same key, so no replay handling is needed here.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExpenseResponse createInTransaction(long groupId, String idempotencyKey, CreateExpenseRequest request,
                                               String bankPurpose) {
        if (request.amountCents() > ExpenseDtos.MAX_AMOUNT_CENTS) {
            throw new BusinessRuleException("Amount is above the limit of " + ExpenseDtos.MAX_AMOUNT_CENTS + " cents");
        }
        return insert(groupId, idempotencyKey, Sha256.hex(request.canonicalForm()), request, bankPurpose).expense();
    }

    public ExpenseResponse get(long groupId, long expenseId) {
        return readTx.execute(status -> toResponse(findInGroup(groupId, expenseId)));
    }

    /**
     * Cancels an expense by writing reversing ledger entries. The expense row and its original
     * entries stay as they are; balances return to where they were before the expense.
     */
    public ExpenseResponse reverse(long groupId, long expenseId) {
        return writeTx.execute(status -> {
            Expense expense = findInGroup(groupId, expenseId);
            List<Posting> reversal = ledgerService.reverse(groupId, EntryKind.EXPENSE, expense.getId()).stream()
                    .map(entry -> new Posting(entry.getMemberId(), entry.getAmountCents()))
                    .toList();
            outbox.record(EventType.EXPENSE_REVERSED, groupId, expense.getId(), expense.getExpenseDate(),
                    expense.getDescription(), expense.getAmountCents(), reversal);
            return ExpenseResponse.from(expense, true);
        });
    }

    private Expense findInGroup(long groupId, long expenseId) {
        return expenses.findByIdAndGroupId(expenseId, groupId)
                .orElseThrow(() -> new NotFoundException("Expense " + expenseId + " not found in group " + groupId));
    }

    private ExpenseResponse toResponse(Expense expense) {
        return ExpenseResponse.from(expense, ledgerService.isReversed(EntryKind.EXPENSE, expense.getId()));
    }

    /**
     * A replay returns the expense as it is now. Expenses never change, so this equals the original
     * response, except that {@code reversed} is true if the expense was reversed in the meantime.
     */
    private Optional<Creation> findEarlier(long groupId, String idempotencyKey, String requestHash) {
        return expenses.findByGroupIdAndIdempotencyKey(groupId, idempotencyKey).map(expense -> {
            if (!expense.getRequestHash().equals(requestHash)) {
                throw new ConflictException(
                        "Idempotency-Key '" + idempotencyKey + "' was already used with a different request body");
            }
            return new Creation(toResponse(expense), true);
        });
    }

    private Creation insert(long groupId, String idempotencyKey, String requestHash, CreateExpenseRequest request,
                            String bankPurpose) {
        Set<Long> memberIds = groupService.memberIds(groupId);
        if (!memberIds.contains(request.payerId())) {
            throw new BusinessRuleException("Payer " + request.payerId() + " is not a member of group " + groupId);
        }
        List<Participant> participants = request.participants().stream()
                .map(p -> new Participant(p.memberId(), p.value()))
                .toList();
        for (Participant p : participants) {
            if (!memberIds.contains(p.memberId())) {
                throw new BusinessRuleException("Participant " + p.memberId() + " is not a member of group " + groupId);
            }
        }

        List<Allocation> allocations = SplitCalculator.split(request.amountCents(), request.splitMode(), participants);
        Expense expense = expenses.save(new Expense(groupId, request.payerId(), request.amountCents(),
                request.description().strip(), request.notes(), request.expenseDate(), request.splitMode(),
                idempotencyKey, requestHash, bankPurpose, allocations));

        List<Posting> postings = postingsFor(expense, allocations);
        ledgerService.post(groupId, EntryKind.EXPENSE, expense.getId(), postings);
        // Same transaction, after the ledger entries (and therefore under the group lock).
        outbox.record(EventType.EXPENSE_RECORDED, groupId, expense.getId(), expense.getExpenseDate(),
                expense.getDescription(), expense.getAmountCents(), postings);
        return new Creation(ExpenseResponse.from(expense, false), false);
    }

    /** Payer is credited the full amount, every participant is debited their share; the sum is zero. */
    static List<Posting> postingsFor(Expense expense, List<Allocation> allocations) {
        List<Posting> postings = new ArrayList<>(allocations.size() + 1);
        postings.add(new Posting(expense.getPayerId(), expense.getAmountCents()));
        for (Allocation allocation : allocations) {
            postings.add(new Posting(allocation.memberId(), -allocation.amountCents()));
        }
        return postings;
    }
}
