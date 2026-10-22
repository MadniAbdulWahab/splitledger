package dev.splitledger.outbox;

import java.time.LocalDate;
import java.util.List;

/**
 * What happened, in a form a consumer can use without access to the database. This is the
 * contract with the statement worker; add fields, never rename or remove them.
 *
 * @param referenceId expense or payment id
 * @param occurredOn  expense date (also for its reversal, so the month of the expense is corrected)
 *                    or the day a payment was recorded
 * @param postings    the ledger entries the event wrote; they sum to zero
 */
public record LedgerEvent(
        EventType type,
        long groupId,
        long referenceId,
        LocalDate occurredOn,
        String description,
        long amountCents,
        List<MemberPosting> postings) {

    public enum EventType {
        EXPENSE_RECORDED,
        EXPENSE_REVERSED,
        PAYMENT_RECORDED
    }

    public record MemberPosting(long memberId, String displayName, long amountCents) {
    }
}
