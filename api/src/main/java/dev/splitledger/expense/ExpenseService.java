package dev.splitledger.expense;

import dev.splitledger.common.BusinessRuleException;
import dev.splitledger.common.ConflictException;
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
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    ExpenseService(ExpenseRepository expenses, GroupService groupService, LedgerService ledgerService,
                   PlatformTransactionManager transactionManager) {
        this.expenses = expenses;
        this.groupService = groupService;
        this.ledgerService = ledgerService;
        this.writeTx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    public record Creation(ExpenseResponse expense, boolean replayed) {
    }

    /**
     * Creates an expense and its ledger entries exactly once per (group, idempotency key).
     * Not annotated with @Transactional: the method needs two separate transactions when two
     * identical requests race (see the catch block).
     */
    public Creation create(long groupId, String idempotencyKey, CreateExpenseRequest request) {
        String requestHash = Sha256.hex(request.canonicalForm());

        Optional<Creation> earlier = readTx.execute(status -> findEarlier(groupId, idempotencyKey, requestHash));
        if (earlier.isPresent()) {
            return earlier.get();
        }
        try {
            return writeTx.execute(status -> insert(groupId, idempotencyKey, requestHash, request, null));
        } catch (DataIntegrityViolationException e) {
            if (!violates(e, IDEMPOTENCY_CONSTRAINT)) {
                throw e;
            }
            // A concurrent request with the same key committed first. Our transaction was rolled
            // back, so read the winner in a new one and answer as if this were a retry.
            return readTx.execute(status -> findEarlier(groupId, idempotencyKey, requestHash)).orElseThrow(() -> e);
        }
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
            ledgerService.reverse(groupId, EntryKind.EXPENSE, expense.getId());
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

        ledgerService.post(groupId, EntryKind.EXPENSE, expense.getId(), postingsFor(expense, allocations));
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

    private static boolean violates(DataIntegrityViolationException e, String constraintName) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return constraintName.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }
}
