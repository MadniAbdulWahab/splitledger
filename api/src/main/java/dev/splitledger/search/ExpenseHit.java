package dev.splitledger.search;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One search result. Shares are left out so a page of results is a single query.
 *
 * <p>{@code change} is DELETED or EDITED when a member corrected this expense ({@code changedBy},
 * {@code changedAt}); {@code replacesExpenseId} is set on the new version of an edited expense.
 * {@code category} is null for expenses from before categories existed.
 */
public record ExpenseHit(
        long id,
        long payerId,
        long amountCents,
        String description,
        String notes,
        LocalDate expenseDate,
        boolean reversed,
        String change,
        Long changedBy,
        Instant changedAt,
        Long replacesExpenseId,
        String category,
        double score) {
}
