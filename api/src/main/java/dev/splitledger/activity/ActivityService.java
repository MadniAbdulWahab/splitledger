package dev.splitledger.activity;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;

/**
 * "Ben added Pizza", "Lucy paid you", "Anna deleted Kino": the group's history as one timeline,
 * newest first. It is read from the tables that already record everything and are append-only
 * (expenses, payments, corrections), plus members and password resets, so the feed cannot say
 * something different from the ledger. Private bank transactions never appear.
 */
@Service
class ActivityService {

    /** One timeline entry. Which fields are set depends on {@code kind}; {@code id} is unique in the feed. */
    record ActivityItem(String id, String kind, Instant at, Long actorMemberId, Long expenseId, Long paymentId,
                        Long memberId, String description, Long amountCents, String category,
                        Long fromMemberId, Long toMemberId) {
    }

    record Page(List<ActivityItem> items, String nextCursor) {
    }

    /** Position after the last item of a page: its time and id, as opaque base64. */
    record Cursor(Instant at, String id) {

        String encode() {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((at + "|" + id).getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String encoded) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
                int bar = raw.indexOf('|');
                return new Cursor(Instant.parse(raw.substring(0, bar)), raw.substring(bar + 1));
            } catch (IllegalArgumentException | IndexOutOfBoundsException | DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid cursor", e);
            }
        }
    }

    // Every branch has the same columns; the first one casts its nulls so PostgreSQL knows the types.
    private static final String FEED = """
            select 'GROUP_CREATED' as kind, g.created_at as at, 'G' || g.id as id,
                   (select min(m.id) from member m where m.group_id = g.id) as actor,
                   null::bigint as expense_id, null::bigint as payment_id, null::bigint as member_id,
                   g.name::text as description, null::bigint as amount_cents, null::text as category,
                   null::bigint as from_member_id, null::bigint as to_member_id
            from expense_group g where g.id = :groupId
            union all
            -- As first entered; the new version of an edited expense appears as EXPENSE_EDITED instead.
            select 'EXPENSE_ADDED', e.created_at, 'E' || e.id, e.created_by, e.id, null, e.payer_id,
                   e.description, e.amount_cents, e.category, null, null
            from expense e
            where e.group_id = :groupId
              and not exists (select 1 from correction c where c.replacement_expense_id = e.id)
            union all
            -- EXPENSE_EDITED, EXPENSE_DELETED or PAYMENT_UNDONE. Edits point to the new version,
            -- deletions to the deleted expense, undos to the payment.
            select c.kind, c.created_at, 'C' || c.id, c.member_id,
                   coalesce(c.replacement_expense_id, c.expense_id), c.payment_id, e.payer_id,
                   coalesce(e.description, p.note), coalesce(e.amount_cents, p.amount_cents), e.category,
                   p.from_member_id, p.to_member_id
            from correction c
            left join expense e on e.id = coalesce(c.replacement_expense_id, c.expense_id)
            left join payment p on p.id = c.payment_id
            where c.group_id = :groupId
            union all
            select 'PAYMENT_RECORDED', p.created_at, 'P' || p.id, p.created_by, null, p.id, null,
                   p.note, p.amount_cents, null, p.from_member_id, p.to_member_id
            from payment p where p.group_id = :groupId
            union all
            select 'MEMBER_ADDED', m.created_at, 'M' || m.id, m.invited_by, null, null, m.id,
                   m.display_name, null, null, null, null
            from member m where m.group_id = :groupId and m.invited_by is not null
            union all
            select 'MEMBER_JOINED', m.joined_at, 'J' || m.id, m.id, null, null, m.id,
                   m.display_name, null, null, null, null
            from member m where m.group_id = :groupId and m.invited_by is not null and m.joined_at is not null
            union all
            -- Visible to everyone on purpose: a reset link lets its creator take over the account.
            select 'PASSWORD_RESET', r.created_at, 'R' || r.id, r.created_by_member_id, null, null,
                   (select m.id from member m where m.group_id = r.group_id and m.user_id = r.user_id),
                   null, null, null, null, null
            from password_reset r where r.group_id = :groupId
            """;

    private static final RowMapper<ActivityItem> ITEM = (rs, rowNum) -> new ActivityItem(
            rs.getString("id"),
            rs.getString("kind"),
            rs.getTimestamp("at").toInstant(),
            rs.getObject("actor", Long.class),
            rs.getObject("expense_id", Long.class),
            rs.getObject("payment_id", Long.class),
            rs.getObject("member_id", Long.class),
            rs.getString("description"),
            rs.getObject("amount_cents", Long.class),
            rs.getString("category"),
            rs.getObject("from_member_id", Long.class),
            rs.getObject("to_member_id", Long.class));

    private final NamedParameterJdbcTemplate jdbc;

    ActivityService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Page feed(long groupId, int limit, Cursor after) {
        MapSqlParameterSource params = new MapSqlParameterSource("groupId", groupId)
                // One row more than requested tells us whether a next page exists.
                .addValue("limit", limit + 1);
        String afterCursor = "";
        if (after != null) {
            params.addValue("afterAt", Timestamp.from(after.at())).addValue("afterId", after.id());
            afterCursor = "where (feed.at, feed.id) < (:afterAt, :afterId)";
        }
        List<ActivityItem> rows = jdbc.query("select * from (%s) feed %s order by feed.at desc, feed.id desc limit :limit"
                .formatted(FEED, afterCursor), params, ITEM);
        if (rows.size() <= limit) {
            return new Page(rows, null);
        }
        List<ActivityItem> page = rows.subList(0, limit);
        ActivityItem last = page.getLast();
        return new Page(List.copyOf(page), new Cursor(last.at(), last.id()).encode());
    }
}
