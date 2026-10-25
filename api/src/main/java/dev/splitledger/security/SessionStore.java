package dev.splitledger.security;

import dev.splitledger.common.SecretCodes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * One row per signed-in device (table {@code user_session}). The token looks like an API token
 * ({@code slt_...}) and is stored only as its SHA-256; signing out sets {@code revoked_at}.
 */
@Repository
class SessionStore {

    /** A phone that is used now and then should not be signed out every few weeks. */
    static final Duration LIFETIME = Duration.ofDays(180);

    private final JdbcTemplate jdbc;

    SessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns the new token; it exists only in this response. */
    String create(long userId) {
        String token = SecretCodes.generate(UserService.TOKEN_PREFIX);
        jdbc.update("insert into user_session (user_id, token_hash, expires_at) values (?, ?, ?)",
                userId, SecretCodes.hash(token), Timestamp.from(Instant.now().plus(LIFETIME)));
        return token;
    }

    Optional<CurrentUser> find(String tokenHash) {
        return jdbc.query("""
                        select u.id, u.display_name, s.id as session_id
                        from user_session s join app_user u on u.id = s.user_id
                        where s.token_hash = ? and s.revoked_at is null and s.expires_at > now()""",
                (rs, rowNum) -> new CurrentUser(rs.getLong("id"), rs.getString("display_name"), rs.getLong("session_id")),
                tokenHash).stream().findFirst();
    }

    void revoke(long sessionId) {
        jdbc.update("update user_session set revoked_at = now() where id = ? and revoked_at is null", sessionId);
    }

    /** Signs out every device of the user except {@code keepSessionId} (null: all of them). */
    void revokeAll(long userId, Long keepSessionId) {
        jdbc.update("update user_session set revoked_at = now() where user_id = ? and revoked_at is null and id <> ?",
                userId, keepSessionId == null ? -1L : keepSessionId);
    }
}
