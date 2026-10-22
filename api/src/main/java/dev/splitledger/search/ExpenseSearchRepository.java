package dev.splitledger.search;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Plain SQL instead of JPA: the query needs PostgreSQL operators (@@, <%), ranking functions and a
 * row-value comparison for keyset pagination, none of which JPQL can express.
 */
@Repository
class ExpenseSearchRepository {

    /**
     * Minimum word similarity for a typo match. The default 0.6 misses one-letter typos in short
     * words ("Lidel" vs "Lidl" scores 0.5); 0.5 catches them without noticeable false hits.
     */
    static final String WORD_SIMILARITY_THRESHOLD = "0.5";

    private static final RowMapper<ExpenseHit> HIT = (rs, rowNum) -> new ExpenseHit(
            rs.getLong("id"),
            rs.getLong("payer_id"),
            rs.getLong("amount_cents"),
            rs.getString("description"),
            rs.getString("notes"),
            rs.getObject("expense_date", LocalDate.class),
            rs.getBoolean("reversed"),
            rs.getString("change"),
            rs.getObject("changed_by", Long.class),
            rs.getTimestamp("changed_at") != null ? rs.getTimestamp("changed_at").toInstant() : null,
            rs.getObject("replaces_expense_id", Long.class),
            rs.getDouble("score"));

    private final NamedParameterJdbcTemplate jdbc;

    ExpenseSearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Query(String sql, MapSqlParameterSource params) {
    }

    /** Must run inside a transaction: the threshold is set with is_local = true for that transaction only. */
    List<ExpenseHit> search(SearchCriteria criteria) {
        useTypoThreshold();
        Query query = build(criteria);
        return jdbc.query(query.sql(), query.params(), HIT);
    }

    void useTypoThreshold() {
        jdbc.queryForObject("select set_config('pg_trgm.word_similarity_threshold', :t, true)",
                new MapSqlParameterSource("t", WORD_SIMILARITY_THRESHOLD), String.class);
    }

    /**
     * Builds the query from fixed SQL fragments; user input only ever travels as bound parameters.
     * Filters that are not set are left out of the SQL completely (instead of "(:x is null or ...)"),
     * so PostgreSQL plans exactly the query that runs and can use the matching indexes.
     */
    Query build(SearchCriteria c) {
        MapSqlParameterSource params = new MapSqlParameterSource("groupId", c.groupId());
        StringBuilder where = new StringBuilder("e.group_id = :groupId");
        String score = "0::float8";
        String textQuery = "";

        if (c.hasText()) {
            params.addValue("q", c.text().strip());
            // OR of both configurations: stemmed German words or the words exactly as typed.
            textQuery = "cross join (select websearch_to_tsquery('german', :q) || websearch_to_tsquery('simple', :q) as tsq) query";
            // Full-text hit (GIN on search_document) or typo/compound hit (GIN trigram on description).
            where.append(" and (e.search_document @@ query.tsq or :q <% e.description)");
            score = "(ts_rank(e.search_document, query.tsq) + word_similarity(:q, e.description))::float8";
        }
        if (c.memberId() != null) {
            params.addValue("memberId", c.memberId());
            where.append(" and (e.payer_id = :memberId or exists (select 1 from expense_share s "
                    + "where s.expense_id = e.id and s.member_id = :memberId))");
        }
        if (c.from() != null) {
            params.addValue("from", c.from());
            where.append(" and e.expense_date >= :from");
        }
        if (c.to() != null) {
            params.addValue("to", c.to());
            where.append(" and e.expense_date <= :to");
        }
        if (c.minCents() != null) {
            params.addValue("min", c.minCents());
            where.append(" and e.amount_cents >= :min");
        }
        if (c.maxCents() != null) {
            params.addValue("max", c.maxCents());
            where.append(" and e.amount_cents <= :max");
        }

        String afterCursor = "";
        if (c.after() != null) {
            params.addValue("afterScore", c.after().score());
            params.addValue("afterId", c.after().id());
            afterCursor = "where (scored.score, scored.id) < (:afterScore, :afterId)";
        }
        // One row more than requested tells us whether a next page exists.
        params.addValue("limit", c.limit() + 1);

        String sql = """
                select page.*,
                       exists (select 1 from ledger_entry original
                               join ledger_entry reversal on reversal.reference_id = original.id and reversal.kind = 'REVERSAL'
                               where original.kind = 'EXPENSE' and original.reference_id = page.id) as reversed,
                       case changed.kind when 'EXPENSE_DELETED' then 'DELETED' when 'EXPENSE_EDITED' then 'EDITED' end as change,
                       changed.member_id as changed_by,
                       changed.created_at as changed_at,
                       replaced.expense_id as replaces_expense_id
                from (
                    select * from (
                        select e.id, e.payer_id, e.amount_cents, e.description, e.notes, e.expense_date,
                               %s as score
                        from expense e %s
                        where %s
                    ) scored
                    %s
                    order by scored.score desc, scored.id desc
                    limit :limit
                ) page
                left join correction changed on changed.expense_id = page.id
                left join correction replaced on replaced.replacement_expense_id = page.id
                order by page.score desc, page.id desc
                """.formatted(score, textQuery, where, afterCursor);
        return new Query(sql, params);
    }
}
