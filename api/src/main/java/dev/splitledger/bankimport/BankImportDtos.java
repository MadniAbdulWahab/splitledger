package dev.splitledger.bankimport;

import dev.splitledger.expense.ExpenseDtos.ParticipantRequest;
import dev.splitledger.expense.SplitMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public final class BankImportDtos {

    private BankImportDtos() {
    }

    /**
     * {@code newTransactions}: offered as expense candidates; {@code duplicates}: already imported
     * before (same fingerprint); {@code skipped}: not imported, with the reason per line.
     */
    public record ImportReport(long importId, StatementFormat format, int rowsRead, int newTransactions,
                               int duplicates, int skippedCount, List<StatementParser.SkippedRow> skipped) {
    }

    public record BankTransactionResponse(long id, LocalDate bookingDate, long amountCents, String counterpartyName,
                                          String counterpartyIban, String purpose, String status, Long expenseId) {
        static BankTransactionResponse from(BankTransaction t) {
            return new BankTransactionResponse(t.getId(), t.getBookingDate(), t.getAmountCents(), t.getCounterpartyName(),
                    t.getCounterpartyIban(), t.getPurpose(), t.getStatus().name(), t.getExpenseId());
        }
    }

    /**
     * Turns a candidate into an expense paid by the account owner. {@code description} defaults to
     * the counterparty name; the bank's purpose text is kept with the expense and becomes searchable.
     */
    public record ConfirmRequest(
            @Size(max = 200) String description,
            @Size(max = 2000) String notes,
            @NotNull SplitMode splitMode,
            @NotEmpty @Size(max = 50) List<@Valid @NotNull ParticipantRequest> participants) {
    }
}
