package dev.splitledger.expense;

import dev.splitledger.expense.ExpenseDtos.CreateExpenseRequest;
import dev.splitledger.expense.ExpenseDtos.ExpenseResponse;
import dev.splitledger.group.GroupService;
import dev.splitledger.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/groups/{groupId}/expenses")
class ExpenseController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final ExpenseService expenseService;
    private final GroupService groupService;

    ExpenseController(ExpenseService expenseService, GroupService groupService) {
        this.expenseService = expenseService;
        this.groupService = groupService;
    }

    /**
     * A retry with the same key and body returns the original expense with the same status and an
     * {@code Idempotent-Replayed: true} header, so clients can retry after a timeout without
     * creating the expense twice.
     */
    @PostMapping
    ResponseEntity<ExpenseResponse> create(@PathVariable long groupId,
                                           @AuthenticationPrincipal CurrentUser user,
                                           @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                                           @Valid @RequestBody CreateExpenseRequest request) {
        checkKey(idempotencyKey);
        return created(expenseService.create(groupId, idempotencyKey, request, groupService.memberIdOf(groupId, user)));
    }

    @GetMapping("/{expenseId}")
    ExpenseResponse get(@PathVariable long groupId, @PathVariable long expenseId) {
        return expenseService.get(groupId, expenseId);
    }

    /**
     * Delete. Nothing is removed: reversing ledger entries cancel the expense, and the history keeps
     * it crossed out with the name of the member who deleted it. The path stays "reversal" because
     * that is what happens in the ledger.
     */
    @PostMapping("/{expenseId}/reversal")
    ExpenseResponse delete(@PathVariable long groupId, @PathVariable long expenseId,
                           @AuthenticationPrincipal CurrentUser user) {
        return expenseService.delete(groupId, expenseId, groupService.memberIdOf(groupId, user));
    }

    /** Edit: the old version is reversed and the body becomes the new version (201 with its id). */
    @PostMapping("/{expenseId}/correction")
    ResponseEntity<ExpenseResponse> edit(@PathVariable long groupId, @PathVariable long expenseId,
                                         @AuthenticationPrincipal CurrentUser user,
                                         @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                                         @Valid @RequestBody CreateExpenseRequest request) {
        checkKey(idempotencyKey);
        return created(expenseService.edit(groupId, expenseId, groupService.memberIdOf(groupId, user),
                idempotencyKey, request));
    }

    private static void checkKey(String idempotencyKey) {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, IDEMPOTENCY_KEY + " must be 1 to 100 characters");
        }
    }

    private static ResponseEntity<ExpenseResponse> created(ExpenseService.Creation creation) {
        ExpenseResponse expense = creation.expense();
        return ResponseEntity
                .created(ServletUriComponentsBuilder.fromCurrentContextPath()
                        .path("/groups/{groupId}/expenses/{id}").buildAndExpand(expense.groupId(), expense.id()).toUri())
                .header(REPLAYED, String.valueOf(creation.replayed()))
                .body(expense);
    }
}
