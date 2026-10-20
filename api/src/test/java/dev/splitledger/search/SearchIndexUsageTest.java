package dev.splitledger.search;

import dev.splitledger.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seeds 50,000 synthetic expenses into one group and checks with EXPLAIN ANALYZE that a search
 * uses both GIN indexes instead of scanning the table. The plan is printed for docs/verification.md.
 */
class SearchIndexUsageTest extends IntegrationTest {

    static final int SEEDED_EXPENSES = 50_000;

    @Autowired
    private ExpenseSearchRepository repository;

    @Autowired
    private ExpenseSearchService searchService;

    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void searchUsesFullTextAndTrigramIndexesOnFiftyThousandExpenses() {
        long groupId = createGroup("Seeded");
        long anna = addMember(groupId, "Anna");
        long ben = addMember(groupId, "Ben");
        long cem = addMember(groupId, "Cem");
        seed(groupId, anna, ben, cem);
        // GIN indexes first collect new rows in an unsorted "pending list" that every search must scan.
        // VACUUM merges it into the index (autovacuum does this in normal operation); without it the
        // planner rightly prefers a sequential scan right after a bulk insert. ANALYZE alone is not enough.
        jdbc.execute("vacuum analyze expense");

        SearchCriteria criteria = new SearchCriteria(groupId, "Geschenk Jonas", null, null, null, null, null, 20, null);
        List<String> plan = new TransactionTemplate(transactionManager).execute(status -> {
            repository.useTypoThreshold();
            ExpenseSearchRepository.Query query = repository.build(criteria);
            return namedJdbc.queryForList("explain (analyze, buffers) " + query.sql(), query.params(), String.class);
        });
        String planText = String.join("\n", plan);
        System.out.println("EXPLAIN ANALYZE for q='Geschenk Jonas' on " + SEEDED_EXPENSES + " expenses:\n" + planText);

        assertThat(planText)
                .contains("Bitmap Index Scan on expense_search_idx")
                .contains("Bitmap Index Scan on expense_description_trgm_idx")
                .doesNotContain("Seq Scan on expense");

        long started = System.nanoTime();
        ExpenseSearchService.Page page = searchService.search(criteria);
        System.out.println("Search returned " + page.items().size() + " hits in "
                + Duration.ofNanos(System.nanoTime() - started).toMillis() + " ms (including the round trip)");
        assertThat(page.items()).hasSize(20).allSatisfy(hit -> assertThat(hit.notes()).isEqualTo("Geschenk für Jonas"));
        assertThat(page.nextCursor()).isNotNull();
    }

    /**
     * Deterministic synthetic data (no random()): descriptions combine 15 purposes and 16 places,
     * every 5th expense has a note, and about 1 in 1000 is the rare "Geschenk für Jonas" the
     * EXPLAIN query looks for. Inserted directly, without ledger entries, because only search is measured.
     */
    private void seed(long groupId, long anna, long ben, long cem) {
        jdbc.update("""
                insert into expense (group_id, payer_id, amount_cents, description, notes, expense_date,
                                     split_mode, idempotency_key, request_hash, created_at)
                select ?, (array[?, ?, ?]::bigint[])[1 + i % 3],
                       100 + (i * 7919) % 20000,
                       (array['Einkauf','Einkäufe','Wocheneinkauf','Getränke','Pizza','Döner','Kino','Bahnticket',
                              'Tankstelle','Miete','Strom','Drogerie','Mensa','Bäckerei','Flohmarkt'])[1 + (i * 31) % 15]
                         || ' ' ||
                       (array['Edeka','Lidl','Rewe','Aldi','Penny','Netto','dm','Rossmann','Bonn','Köln',
                              'Poppelsdorf','Kessenich','Beuel','Ehrenfeld','Siegburg','Brühl'])[1 + (i * 17) % 16],
                       case when i % 997 = 0 then 'Geschenk für Jonas'
                            when i % 5 = 0 then (array['WG-Party','Wochenende','Ausflug','Spieleabend'])[1 + (i / 5) % 4]
                       end,
                       date '2024-01-01' + (i % 1000),
                       'EQUAL', 'seed-' || i, repeat('0', 64), now()
                from generate_series(1, ?) as i
                """, groupId, anna, ben, cem, SEEDED_EXPENSES);
    }
}
