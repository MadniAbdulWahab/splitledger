package dev.splitledger.security;

import dev.splitledger.common.BusinessRuleException;
import dev.splitledger.common.NotFoundException;
import dev.splitledger.common.SecretCodes;
import dev.splitledger.group.GroupService;
import dev.splitledger.group.Member;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Forgotten passwords without an email server: any groupmate can create a one-time reset link for
 * a friend and send it to them like an invite link. The link is valid for a day and works once;
 * using it signs out every device. Because a groupmate could also use the link themselves, every
 * reset appears in the group's activity feed with who created it.
 */
@Service
public class PasswordResetService {

    static final String PREFIX = "rst_";
    static final Duration VALID_FOR = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final GroupService groupService;
    private final UserService userService;

    PasswordResetService(JdbcTemplate jdbc, GroupService groupService, UserService userService) {
        this.jdbc = jdbc;
        this.groupService = groupService;
        this.userService = userService;
    }

    public record ResetLink(long memberId, String resetCode, Instant expiresAt) {
    }

    /** {@code username} is null when the account never had one; the reset then asks for it. */
    public record ResetPreview(String displayName, String username) {
    }

    private record PendingReset(long id, long userId) {
    }

    @Transactional
    public ResetLink create(long groupId, long memberId, CurrentUser creator) {
        Member member = groupService.member(memberId)
                .filter(m -> m.getGroupId() == groupId)
                .orElseThrow(() -> new NotFoundException("Member " + memberId + " not found in group " + groupId));
        if (member.getUserId() == null) {
            throw new BusinessRuleException(member.getDisplayName() + " has not joined yet; send the invite link instead");
        }
        if (member.getUserId() == creator.id()) {
            throw new BusinessRuleException("Change your own password in your account settings");
        }
        String code = SecretCodes.generate(PREFIX);
        Instant expiresAt = Instant.now().plus(VALID_FOR).truncatedTo(ChronoUnit.SECONDS);
        jdbc.update("""
                        insert into password_reset (user_id, group_id, created_by_member_id, code_hash, created_at, expires_at)
                        values (?, ?, ?, ?, ?, ?)""",
                member.getUserId(), groupId, groupService.memberIdOf(groupId, creator), SecretCodes.hash(code),
                // The application's clock, like every other entry of the activity feed.
                Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)), Timestamp.from(expiresAt));
        return new ResetLink(memberId, code, expiresAt);
    }

    @Transactional(readOnly = true)
    public ResetPreview preview(String code) {
        PendingReset reset = pending(code, false);
        UserService.Account account = userService.account(reset.userId());
        String displayName = jdbc.queryForObject("select display_name from app_user where id = ?", String.class, reset.userId());
        return new ResetPreview(displayName, account.username());
    }

    /** Uses the link (once), sets the password and signs the user in on this device. */
    @Transactional
    public UserService.Registration redeem(String code, String username, String newPassword) {
        PendingReset reset = pending(code, true);
        jdbc.update("update password_reset set used_at = now() where id = ?", reset.id());
        return userService.resetPassword(reset.userId(), username, newPassword);
    }

    /** The row lock makes a second, simultaneous use wait and then find the link used. */
    private PendingReset pending(String code, boolean lock) {
        return jdbc.query("select id, user_id from password_reset where code_hash = ? and used_at is null and expires_at > now()"
                                + (lock ? " for update" : ""),
                        (rs, rowNum) -> new PendingReset(rs.getLong("id"), rs.getLong("user_id")),
                        SecretCodes.hash(code.strip()))
                .stream().findFirst()
                .orElseThrow(() -> new NotFoundException("This reset link is unknown, expired or was already used"));
    }
}
