package dev.splitledger.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * Records who deleted or edited an expense and who undid a payment (table {@code correction}).
 * The reversing ledger entries are written by {@link LedgerService#reverse}; this only adds the
 * person and the time, in the same transaction.
 */
@Repository
public class CorrectionLog {

    public enum Kind { EXPENSE_DELETED, EXPENSE_EDITED, PAYMENT_UNDONE }

    public record Entry(Kind kind, long memberId, Instant at) {
    }

    private final JdbcTemplate jdbc;

    CorrectionLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void expenseDeleted(long groupId, long expenseId, long memberId) {
        jdbc.update("""
                insert into correction (group_id, kind, expense_id, member_id, created_at)
                values (?, 'EXPENSE_DELETED', ?, ?, ?)""", groupId, expenseId, memberId, now());
    }

    public void expenseEdited(long groupId, long expenseId, long replacementId, long memberId) {
        jdbc.update("""
                insert into correction (group_id, kind, expense_id, replacement_expense_id, member_id, created_at)
                values (?, 'EXPENSE_EDITED', ?, ?, ?, ?)""", groupId, expenseId, replacementId, memberId, now());
    }

    public void paymentUndone(long groupId, long paymentId, long memberId) {
        jdbc.update("""
                insert into correction (group_id, kind, payment_id, member_id, created_at)
                values (?, 'PAYMENT_UNDONE', ?, ?, ?)""", groupId, paymentId, memberId, now());
    }

    /**
     * The application's clock, like the created_at of expenses and payments, so the activity feed
     * orders all of them by one clock (the database container's clock can differ by milliseconds).
     */
    private static Timestamp now() {
        return Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /** Payment id to the person who undid it, for one group. */
    public Map<Long, Entry> undonePayments(long groupId) {
        Map<Long, Entry> result = new HashMap<>();
        jdbc.query("select payment_id, member_id, created_at from correction where group_id = ? and kind = 'PAYMENT_UNDONE'",
                rs -> {
                    result.put(rs.getLong("payment_id"), new Entry(Kind.PAYMENT_UNDONE, rs.getLong("member_id"),
                            rs.getTimestamp("created_at").toInstant()));
                }, groupId);
        return result;
    }
}
