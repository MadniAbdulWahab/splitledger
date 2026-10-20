package dev.splitledger.ledger;

import dev.splitledger.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerChainTest extends IntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long groupId;
    private long anna;
    private long ben;

    @BeforeEach
    void setUpGroupWithThreeExpenses() {
        groupId = createGroup("Chain");
        anna = myMemberId(groupId);
        ben = addMember(groupId, "Ben");
        createExpense(groupId, anna, 1000, "Groceries", anna, ben);
        createExpense(groupId, ben, 3000, "Train tickets", anna, ben);
        createExpense(groupId, anna, 999, "Coffee", anna, ben);
        // 3 expenses x 3 entries (payer credit + two shares) = seq 1..9
    }

    @Test
    void intactChainIsReportedValidWithItsHeadHash() {
        assertThat(mvc.get().uri("/groups/{id}/verify", groupId))
                .hasStatusOk()
                .bodyJson().extractingPath("$.valid").isEqualTo(true);

        ChainVerification result = ledgerService.verify(groupId);
        assertThat(result.entriesChecked()).isEqualTo(9);
        assertThat(result.balanced()).isTrue();
        assertThat(result.headHash()).isEqualTo(
                jdbc.queryForObject("select hash from ledger_entry where group_id = ? and seq = 9", String.class, groupId));
    }

    @Test
    void entriesAreLinkedFromTheGenesisHash() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select previous_hash, hash from ledger_entry where group_id = ? order by seq", groupId);

        assertThat(rows.getFirst().get("previous_hash")).isEqualTo(LedgerHasher.GENESIS_HASH);
        for (int i = 1; i < rows.size(); i++) {
            assertThat(rows.get(i).get("previous_hash")).isEqualTo(rows.get(i - 1).get("hash"));
        }
    }

    @Test
    void databaseRejectsUpdateDeleteAndTruncate() {
        assertThatThrownBy(() -> jdbc.update("update ledger_entry set amount_cents = 1 where group_id = ?", groupId))
                .hasMessageContaining("ledger_entry is append-only: UPDATE is not allowed");
        assertThatThrownBy(() -> jdbc.update("delete from ledger_entry where group_id = ?", groupId))
                .hasMessageContaining("ledger_entry is append-only: DELETE is not allowed");
        assertThatThrownBy(() -> jdbc.execute("truncate ledger_entry"))
                .hasMessageContaining("ledger_entry is append-only: TRUNCATE is not allowed");
        assertThatThrownBy(() -> jdbc.update("update expense set amount_cents = 1 where group_id = ?", groupId))
                .hasMessageContaining("expense is append-only");
        assertThatThrownBy(() -> jdbc.update("delete from expense_share where group_id = ?", groupId))
                .hasMessageContaining("expense_share is append-only");

        assertThat(ledgerService.verify(groupId).valid()).isTrue();
    }

    @Test
    void detectsAChangedAmount() {
        tamper(db -> db.update("update ledger_entry set amount_cents = amount_cents + 100 where group_id = ? and seq = 5", groupId));

        ChainVerification result = ledgerService.verify(groupId);

        assertThat(result.valid()).isFalse();
        assertThat(result.firstBrokenEntry().seq()).isEqualTo(5);
        assertThat(result.firstBrokenEntry().problem()).contains("content does not match");
    }

    @Test
    void detectsADeletedEntry() {
        tamper(db -> db.update("delete from ledger_entry where group_id = ? and seq = 4", groupId));

        ChainVerification result = ledgerService.verify(groupId);

        assertThat(result.valid()).isFalse();
        assertThat(result.firstBrokenEntry().seq()).isEqualTo(5);
        assertThat(result.firstBrokenEntry().problem()).contains("expected seq 4");
    }

    @Test
    void detectsAChangedEntryEvenWhenItsOwnHashWasRecomputed() {
        tamper(db -> {
            db.update("update ledger_entry set amount_cents = amount_cents + 100 where group_id = ? and seq = 5", groupId);
            rehash(db, 5);
        });

        ChainVerification result = ledgerService.verify(groupId);

        // Entry 5 now looks consistent, but entry 6 still points to the old hash of entry 5.
        assertThat(result.valid()).isFalse();
        assertThat(result.firstBrokenEntry().seq()).isEqualTo(6);
        assertThat(result.firstBrokenEntry().problem()).contains("previous_hash does not match");
    }

    /**
     * The known limit: whoever controls the database can rewrite the whole chain after a change.
     * The chain is then valid again, but its head hash differs from the one members noted earlier.
     */
    @Test
    void rewritingTheWholeChainChangesTheHeadHash() {
        String publishedHead = ledgerService.verify(groupId).headHash();

        tamper(db -> {
            db.update("update ledger_entry set amount_cents = amount_cents + 100 where group_id = ? and seq = 2", groupId);
            db.update("update ledger_entry set amount_cents = amount_cents - 100 where group_id = ? and seq = 3", groupId);
            for (long seq = 2; seq <= 9; seq++) {
                if (seq > 2) {
                    db.update("""
                            update ledger_entry set previous_hash = (
                                select hash from ledger_entry where group_id = ? and seq = ?)
                            where group_id = ? and seq = ?""", groupId, seq - 1, groupId, seq);
                }
                rehash(db, seq);
            }
        });

        ChainVerification result = ledgerService.verify(groupId);
        assertThat(result.valid()).isTrue();
        assertThat(result.headHash()).isNotEqualTo(publishedHead);
    }

    @Test
    void reversalCancelsTheExpenseOnceAndKeepsTheChainValid() {
        long expenseId = createExpense(groupId, ben, 2000, "Wrong amount", anna, ben);
        List<MemberBalance> before = ledgerService.balances(groupId);

        assertThat(mvc.post().uri("/groups/{g}/expenses/{e}/reversal", groupId, expenseId))
                .hasStatusOk()
                .bodyJson().extractingPath("$.reversed").isEqualTo(true);
        assertThat(mvc.post().uri("/groups/{g}/expenses/{e}/reversal", groupId, expenseId)).hasStatus(409);

        // Ben paid 20.00 for both: +10.00 for Ben, -10.00 for Anna; the reversal takes exactly that back.
        List<MemberBalance> after = ledgerService.balances(groupId);
        assertThat(after.get(0).balanceCents()).isEqualTo(before.get(0).balanceCents() + 1000);
        assertThat(after.get(1).balanceCents()).isEqualTo(before.get(1).balanceCents() - 1000);
        assertThat(mvc.get().uri("/groups/{g}/expenses/{e}", groupId, expenseId))
                .bodyJson().extractingPath("$.reversed").isEqualTo(true);

        List<Map<String, Object>> reversals = jdbc.queryForList("""
                select r.amount_cents as reversal, o.amount_cents as original
                from ledger_entry r join ledger_entry o on r.reference_id = o.id
                where r.kind = 'REVERSAL' and o.kind = 'EXPENSE' and o.reference_id = ?""", expenseId);
        assertThat(reversals).hasSize(3)
                .allSatisfy(row -> assertThat((Long) row.get("reversal")).isEqualTo(-(Long) row.get("original")));

        ChainVerification chain = ledgerService.verify(groupId);
        assertThat(chain.valid()).isTrue();
        assertThat(chain.entriesChecked()).isEqualTo(15);
        assertThat(chain.balanced()).isTrue();
    }

    @Test
    void databaseAllowsEachEntryToBeReversedOnlyOnce() {
        long entryId = jdbc.queryForObject("select id from ledger_entry where group_id = ? and seq = 1", Long.class, groupId);

        assertThatThrownBy(() -> jdbc.update("""
                insert into ledger_entry (group_id, member_id, amount_cents, kind, reference_id, seq, previous_hash, hash)
                select group_id, member_id, -amount_cents, 'REVERSAL', id, s, 'x', 'x'
                from ledger_entry, (values (100), (101)) as v(s)
                where id = ?""", entryId))
                .hasMessageContaining("ledger_entry_reversed_once");
    }

    /** Simulates someone with superuser access: triggers are off for this one transaction only. */
    private void tamper(Consumer<JdbcTemplate> change) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("set local session_replication_role = replica");
            change.accept(jdbc);
        });
    }

    /** Recomputes one entry's hash from its current content, the way an attacker would. */
    private void rehash(JdbcTemplate db, long seq) {
        Map<String, Object> row = db.queryForMap("select * from ledger_entry where group_id = ? and seq = ?", groupId, seq);
        String hash = LedgerHasher.hash((String) row.get("previous_hash"), groupId, seq,
                (Long) row.get("member_id"), (Long) row.get("amount_cents"),
                EntryKind.valueOf((String) row.get("kind")), (Long) row.get("reference_id"),
                ((Timestamp) row.get("created_at")).toInstant());
        db.update("update ledger_entry set hash = ? where group_id = ? and seq = ?", hash, groupId, seq);
    }
}
