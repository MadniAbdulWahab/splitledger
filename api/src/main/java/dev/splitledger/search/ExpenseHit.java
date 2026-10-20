package dev.splitledger.search;

import java.time.LocalDate;

/** One search result. Shares are left out so a page of results is a single query. */
public record ExpenseHit(
        long id,
        long payerId,
        long amountCents,
        String description,
        String notes,
        LocalDate expenseDate,
        boolean reversed,
        double score) {
}
