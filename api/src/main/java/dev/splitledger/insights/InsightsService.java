package dev.splitledger.insights;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the group's money went over the last months: per month, per category and per payer. Only
 * live expenses count: a deleted expense and the old version of an edited one have reversing ledger
 * entries and are left out, so the numbers match the balances. Plain SQL, like the search, because
 * the sums are grouped by month and category in the database.
 */
@Service
class InsightsService {

    /** Months follow the group's calendar, as for GiroCode texts and statements. */
    static final ZoneId GROUP_TIME_ZONE = ZoneId.of("Europe/Berlin");

    /** An expense counts unless its ledger entries were reversed (deleted, or replaced by an edit). */
    private static final String LIVE = """
            e.group_id = :groupId and e.expense_date between :from and :to
            and not exists (select 1 from ledger_entry original
                            join ledger_entry reversal on reversal.reference_id = original.id and reversal.kind = 'REVERSAL'
                            where original.kind = 'EXPENSE' and original.reference_id = e.id)""";

    private final NamedParameterJdbcTemplate jdbc;

    InsightsService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code myShareCents}: what the signed-in member consumed, not what they paid. */
    record MonthTotal(String month, long totalCents, long myShareCents) {
    }

    /** {@code category} is OTHER for expenses from before categories existed. */
    record CategoryTotal(String category, long totalCents, long myShareCents, long expenses) {
    }

    record PayerTotal(long memberId, long paidCents) {
    }

    record Insights(LocalDate from, LocalDate to, long totalCents, long myShareCents,
                    List<MonthTotal> months, List<CategoryTotal> categories, List<PayerTotal> payers) {
    }

    /** The current month and the {@code months - 1} before it; months without expenses show zero. */
    @Transactional(readOnly = true)
    public Insights insights(long groupId, long memberId, int months) {
        YearMonth current = YearMonth.now(GROUP_TIME_ZONE);
        YearMonth first = current.minusMonths(months - 1L);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("groupId", groupId)
                .addValue("me", memberId)
                .addValue("from", first.atDay(1))
                .addValue("to", current.atEndOfMonth());

        Map<String, MonthTotal> byMonth = new LinkedHashMap<>();
        for (YearMonth m = first; !m.isAfter(current); m = m.plusMonths(1)) {
            byMonth.put(m.toString(), new MonthTotal(m.toString(), 0, 0));
        }
        jdbc.query("""
                        select to_char(e.expense_date, 'YYYY-MM') as month, sum(e.amount_cents) as total,
                               coalesce(sum(mine.amount_cents), 0) as my_share
                        from expense e
                        left join expense_share mine on mine.expense_id = e.id and mine.member_id = :me
                        where %s
                        group by 1""".formatted(LIVE), params,
                rs -> {
                    String month = rs.getString("month");
                    byMonth.put(month, new MonthTotal(month, rs.getLong("total"), rs.getLong("my_share")));
                });

        List<CategoryTotal> categories = jdbc.query("""
                        select coalesce(e.category, 'OTHER') as category, sum(e.amount_cents) as total,
                               coalesce(sum(mine.amount_cents), 0) as my_share, count(*) as expenses
                        from expense e
                        left join expense_share mine on mine.expense_id = e.id and mine.member_id = :me
                        where %s
                        group by 1
                        order by total desc, category""".formatted(LIVE), params,
                (rs, rowNum) -> new CategoryTotal(rs.getString("category"), rs.getLong("total"),
                        rs.getLong("my_share"), rs.getLong("expenses")));

        List<PayerTotal> payers = jdbc.query("""
                        select e.payer_id, sum(e.amount_cents) as paid
                        from expense e
                        where %s
                        group by 1
                        order by paid desc, payer_id""".formatted(LIVE), params,
                (rs, rowNum) -> new PayerTotal(rs.getLong("payer_id"), rs.getLong("paid")));

        List<MonthTotal> monthTotals = new ArrayList<>(byMonth.values());
        return new Insights(first.atDay(1), current.atEndOfMonth(),
                monthTotals.stream().mapToLong(MonthTotal::totalCents).sum(),
                monthTotals.stream().mapToLong(MonthTotal::myShareCents).sum(),
                monthTotals, categories, payers);
    }
}
