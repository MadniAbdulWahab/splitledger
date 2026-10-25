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
import dev.splitledger.ledger.CorrectionLog;
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
    private final CorrectionLog corrections;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    ExpenseService(ExpenseRepository expenses, GroupService groupService, LedgerService ledgerService,
                   IdempotentCreator idempotentCreator, OutboxService outbox, CorrectionLog corrections,
                   PlatformTransactionManager transactionManager) {
        this.expenses = expenses;
        this.groupService = groupService;
        this.ledgerService = ledgerService;
        this.idempotentCreator = idempotentCreator;
        this.outbox = outbox;
        this.corrections = corrections;
        this.writeTx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    public record Creation(ExpenseResponse expense, boolean replayed) {
    }

    /** Creates an expense and its ledger entries exactly once per (group, idempotency key). */
    public Creation create(long groupId, String idempotencyKey, CreateExpenseRequest request, long createdBy) {
        String requestHash = Sha256.hex(request.canonicalForm());
        return idempotentCreator.create(IDEMPOTENCY_CONSTRAINT,
                () -> findEarlier(groupId, idempotencyKey, requestHash),
                () -> insert(groupId, idempotencyKey, requestHash, request, null, createdBy)).value();
    }

    /**
     * Creates an expense inside the caller's transaction (used when a bank transaction is
     * confirmed: the expense and the status change must commit together). The caller holds a lock
     * that prevents a second attempt with the same key, so no replay handling is needed here.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExpenseResponse createInTransaction(long groupId, String idempotencyKey, CreateExpenseRequest request,
                                               String bankPurpose, long createdBy) {
        if (request.amountCents() > ExpenseDtos.MAX_AMOUNT_CENTS) {
            throw new BusinessRuleException("Amount is above the limit of " + ExpenseDtos.MAX_AMOUNT_CENTS + " cents");
        }
        return insert(groupId, idempotencyKey, Sha256.hex(request.canonicalForm()), request, bankPurpose, createdBy).expense();
    }

    public ExpenseResponse get(long groupId, long expenseId) {
        return readTx.execute(status -> toResponse(findInGroup(groupId, expenseId)));
    }

    /**
     * Deletes an expense the ledger way: reversing entries cancel it, the expense row and its
     * original entries stay, and the correction log records who did it. Balances return to where
     * they were before the expense.
     */
    public ExpenseResponse delete(long groupId, long expenseId, long memberId) {
        return writeTx.execute(status -> {
            Expense expense = cancel(groupId, expenseId);
            corrections.expenseDeleted(groupId, expenseId, memberId);
            return ExpenseResponse.from(expense, true);
        });
    }

    /**
     * Edits an expense: the old version is reversed and the new one created in one transaction, so
     * the group never sees both or neither. Idempotent per key like {@link #create}; the original
     * expense id is part of the request hash, so a key cannot be reused to edit another expense.
     * An expense is corrected at most once (409 afterwards); the new version can be edited again.
     */
    public Creation edit(long groupId, long expenseId, long memberId, String idempotencyKey, CreateExpenseRequest request) {
        String requestHash = Sha256.hex("edit of " + expenseId + "\n" + request.canonicalForm());
        try {
            return idempotentCreator.create(IDEMPOTENCY_CONSTRAINT,
                    () -> findEarlier(groupId, idempotencyKey, requestHash),
                    () -> {
                        Expense original = cancel(groupId, expenseId);
                        Creation created = insert(groupId, idempotencyKey, requestHash, request, original.getBankPurpose(), memberId);
                        corrections.expenseEdited(groupId, expenseId, created.expense().id(), memberId);
                        return created;
                    }).value();
        } catch (ConflictException alreadyCorrected) {
            // A retry with the same key that ran at the same moment waited for the group lock and
            // then found the expense reversed. It gets the first request's result, like a replay.
            return readTx.execute(status -> findEarlier(groupId, idempotencyKey, requestHash))
                    .orElseThrow(() -> alreadyCorrected);
        }
    }

    /** Reversing entries plus the outbox event; the caller's transaction adds the correction record. */
    private Expense cancel(long groupId, long expenseId) {
        Expense expense = findInGroup(groupId, expenseId);
        List<Posting> reversal = ledgerService.reverse(groupId, EntryKind.EXPENSE, expense.getId()).stream()
                .map(entry -> new Posting(entry.getMemberId(), entry.getAmountCents()))
                .toList();
        outbox.record(EventType.EXPENSE_REVERSED, groupId, expense.getId(), expense.getExpenseDate(),
                expense.getDescription(), expense.getAmountCents(), reversal);
        return expense;
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
                            String bankPurpose, long createdBy) {
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
        // The payer's credit and own share would cancel out: no balance changes, nothing is shared.
        if (participants.stream().allMatch(p -> p.memberId() == request.payerId())) {
            throw new BusinessRuleException("Only the payer takes part, so nothing is shared; add at least one other participant");
        }

        List<Allocation> allocations = SplitCalculator.split(request.amountCents(), request.splitMode(), participants);
        Expense expense = expenses.save(new Expense(groupId, request.payerId(), request.amountCents(),
                request.description().strip(), request.notes(), request.expenseDate(), request.splitMode(),
                idempotencyKey, requestHash, bankPurpose, allocations, request.category(), createdBy));

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
