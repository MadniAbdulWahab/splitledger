package dev.splitledger.expense;

import dev.splitledger.common.CanonicalText;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
            @NotEmpty @Size(max = 50) List<@Valid @NotNull ParticipantRequest> participants,
            Category category) {

        /**
         * Deterministic text form of the request, hashed to detect "same Idempotency-Key, different
         * body". Participants are sorted so their order in the JSON does not matter. The category is
         * only added when set, so requests from before categories existed keep their hash.
         */
        String canonicalForm() {
            List<Object> values = new ArrayList<>();
            values.add(payerId);
            values.add(amountCents);
            values.add(description);
            values.add(notes);
            values.add(expenseDate);
            values.add(splitMode);
            participants.stream()
                    .sorted(Comparator.comparing(ParticipantRequest::memberId))
                    .forEach(p -> {
                        values.add(p.memberId());
                        values.add(p.value());
                    });
            if (category != null) {
                values.add("category " + category);
            }
            return CanonicalText.of(values.toArray());
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
            String bankPurpose,
            boolean reversed,
            Instant createdAt,
            Category category,
            Long createdBy) {

        static ExpenseResponse from(Expense expense, boolean reversed) {
            List<ShareResponse> shares = expense.getShares().stream()
                    .map(s -> new ShareResponse(s.getMemberId(), s.getAmountCents(), s.getShareUnits()))
                    .toList();
            return new ExpenseResponse(expense.getId(), expense.getGroupId(), expense.getPayerId(),
                    expense.getAmountCents(), expense.getDescription(), expense.getNotes(),
                    expense.getExpenseDate(), expense.getSplitMode(), shares, expense.getBankPurpose(), reversed,
                    expense.getCreatedAt(), expense.getCategory(), expense.getCreatedBy());
        }
    }
}
