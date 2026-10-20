package dev.splitledger.expense;

import dev.splitledger.expense.ExpenseDtos.CreateExpenseRequest;
import dev.splitledger.expense.ExpenseDtos.ExpenseResponse;
import jakarta.validation.Valid;
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

    ExpenseController(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    /**
     * A retry with the same key and body returns the original expense with the same status and an
     * {@code Idempotent-Replayed: true} header, so clients can retry after a timeout without
     * creating the expense twice.
     */
    @PostMapping
    ResponseEntity<ExpenseResponse> create(@PathVariable long groupId,
                                           @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                                           @Valid @RequestBody CreateExpenseRequest request) {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, IDEMPOTENCY_KEY + " must be 1 to 100 characters");
        }
        ExpenseService.Creation creation = expenseService.create(groupId, idempotencyKey, request);
        ExpenseResponse expense = creation.expense();
        return ResponseEntity
                .created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(expense.id()).toUri())
                .header(REPLAYED, String.valueOf(creation.replayed()))
                .body(expense);
    }

    @GetMapping("/{expenseId}")
    ExpenseResponse get(@PathVariable long groupId, @PathVariable long expenseId) {
        return expenseService.get(groupId, expenseId);
    }

    /** Corrections never edit: a wrong expense is reversed and, if needed, entered again. */
    @PostMapping("/{expenseId}/reversal")
    ExpenseResponse reverse(@PathVariable long groupId, @PathVariable long expenseId) {
        return expenseService.reverse(groupId, expenseId);
    }
}
