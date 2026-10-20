package dev.splitledger.expense;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

public final class ExpenseDtos {

    /** 1,000,000.00 EUR; far above any shared bill, low enough that amount * share units never overflows. */
    static final long MAX_AMOUNT_CENTS = 100_000_000L;

    private ExpenseDtos() {
    }

    public record CreateExpenseRequest(
            @NotNull Long payerId,
            @NotNull @Positive @Max(MAX_AMOUNT_CENTS) Long amountCents,
            @NotBlank @Size(max = 200) String description,
            @Size(max = 2000) String notes,
            @NotNull LocalDate expenseDate,
            @NotNull SplitMode splitMode,
            @NotEmpty @Size(max = 50) List<@Valid @NotNull ParticipantRequest> participants) {

        /**
         * Deterministic text form of the request, hashed to detect "same Idempotency-Key, different
         * body". Each field is length-prefixed so "ab"+"c" and "a"+"bc" cannot collide, and
         * participants are sorted so their order in the JSON does not matter.
         */
        String canonicalForm() {
            StringBuilder out = new StringBuilder();
            append(out, payerId);
            append(out, amountCents);
            append(out, description);
            append(out, notes);
            append(out, expenseDate);
            append(out, splitMode);
            participants.stream()
                    .sorted(Comparator.comparing(ParticipantRequest::memberId))
                    .forEach(p -> {
                        append(out, p.memberId());
                        append(out, p.value());
                    });
            return out.toString();
        }

        private static void append(StringBuilder out, Object value) {
            if (value == null) {
                out.append("-1:|");
                return;
            }
            String text = value.toString();
            out.append(text.length()).append(':').append(text).append('|');
        }
    }

    /** {@code value} means cents for EXACT, share units for SHARES and must be empty for EQUAL. */
    public record ParticipantRequest(@NotNull Long memberId, @Positive Long value) {
    }

    public record ShareResponse(long memberId, long amountCents, Integer shareUnits) {
    }

    public record ExpenseResponse(
            long id,
            long groupId,
            long payerId,
            long amountCents,
            String description,
            String notes,
            LocalDate expenseDate,
            SplitMode splitMode,
            List<ShareResponse> shares,
            boolean reversed,
            Instant createdAt) {

        static ExpenseResponse from(Expense expense, boolean reversed) {
            List<ShareResponse> shares = expense.getShares().stream()
                    .map(s -> new ShareResponse(s.getMemberId(), s.getAmountCents(), s.getShareUnits()))
                    .toList();
            return new ExpenseResponse(expense.getId(), expense.getGroupId(), expense.getPayerId(),
                    expense.getAmountCents(), expense.getDescription(), expense.getNotes(),
                    expense.getExpenseDate(), expense.getSplitMode(), shares, reversed, expense.getCreatedAt());
        }
    }
}
