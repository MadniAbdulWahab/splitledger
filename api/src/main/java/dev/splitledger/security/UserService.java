package dev.splitledger.security;

import dev.splitledger.common.BusinessRuleException;
import dev.splitledger.common.ConflictException;
import dev.splitledger.common.NotFoundException;
import dev.splitledger.common.SecretCodes;
import dev.splitledger.group.GroupService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class UserService {

    static final String TOKEN_PREFIX = "slt_";

    private static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,30}");

    private final AppUserRepository users;
    private final SessionStore sessions;
    private final Passwords passwords;
    private final LoginThrottle throttle;
    private final GroupService groupService;

    UserService(AppUserRepository users, SessionStore sessions, Passwords passwords, LoginThrottle throttle,
                GroupService groupService) {
        this.users = users;
        this.sessions = sessions;
        this.passwords = passwords;
        this.throttle = throttle;
        this.groupService = groupService;
    }

    /** The token is returned exactly once; afterwards only its hash exists. */
    public record Registration(long id, String displayName, String token) {
    }

    /** What the account settings show; {@code username} is null for accounts with only an API token. */
    public record Account(String username, boolean hasPassword) {
    }

    /** An account with an API token only, for scripts and the API docs. */
    @Transactional
    public Registration register(String displayName) {
        String token = SecretCodes.generate(TOKEN_PREFIX);
        AppUser user = users.save(new AppUser(displayName.strip(), SecretCodes.hash(token)));
        return new Registration(user.getId(), user.getDisplayName(), token);
    }

    /** An account people sign in to with username and password; the token is the first session. */
    @Transactional
    public Registration register(String displayName, String username, String password) {
        String name = normalizeUsername(username);
        if (users.existsByUsername(name)) {
            throw new ConflictException("The username '" + name + "' is taken");
        }
        // The API token of a password account is never shown, so it is random and unusable.
        AppUser user = new AppUser(displayName.strip(), SecretCodes.hash(SecretCodes.generate(TOKEN_PREFIX)));
        user.changeUsername(name);
        user.changePasswordHash(passwords.hash(password));
        users.save(user);
        return new Registration(user.getId(), user.getDisplayName(), sessions.create(user.getId()));
    }

    /**
     * Opening an invite link: creates the account under the member's name and joins the group in
     * one transaction. The invite code is the permission, so no registration code is needed; an
     * unknown code or a taken username leaves nothing behind.
     */
    @Transactional
    public Registration signUpWithInvitation(String inviteCode, String username, String password) {
        String memberName = groupService.previewInvitation(inviteCode).memberName();
        Registration registration = register(memberName, username, password);
        groupService.acceptInvitation(inviteCode, new CurrentUser(registration.id(), registration.displayName()));
        return registration;
    }

    /** Same answer for an unknown username and a wrong password, so usernames cannot be probed. */
    @Transactional
    public String login(String username, String password) {
        String name = username.strip().toLowerCase(Locale.ROOT);
        if (throttle.isLocked(name)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many wrong passwords. Wait a minute and try again.");
        }
        Optional<AppUser> user = users.findByUsername(name);
        if (!passwords.matches(password, user.map(AppUser::getPasswordHash).orElse(null))) {
            throttle.failed(name);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong username or password");
        }
        throttle.succeeded(name);
        return sessions.create(user.get().getId());
    }

    @Transactional(readOnly = true)
    Optional<CurrentUser> authenticate(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        String hash = SecretCodes.hash(token);
        return sessions.find(hash).or(() -> users.findByTokenHash(hash)
                .map(user -> new CurrentUser(user.getId(), user.getDisplayName())));
    }

    @Transactional(readOnly = true)
    public Account account(long userId) {
        AppUser user = find(userId);
        return new Account(user.getUsername(), user.getPasswordHash() != null);
    }

    @Transactional
    public Account changeUsername(CurrentUser current, String username) {
        AppUser user = find(current.id());
        String name = normalizeUsername(username);
        if (!name.equals(user.getUsername()) && users.existsByUsername(name)) {
            throw new ConflictException("The username '" + name + "' is taken");
        }
        user.changeUsername(name);
        return new Account(name, user.getPasswordHash() != null);
    }

    /**
     * Needs the current password once one is set. Signs out every other device, so a changed
     * password locks out whoever knew the old one.
     */
    @Transactional
    public Account changePassword(CurrentUser current, String currentPassword, String newPassword) {
        AppUser user = find(current.id());
        if (user.getPasswordHash() != null && (currentPassword == null || !passwords.matches(currentPassword, user.getPasswordHash()))) {
            throw new BusinessRuleException("The current password is wrong");
        }
        user.changePasswordHash(passwords.hash(newPassword));
        sessions.revokeAll(user.getId(), current.sessionId());
        return new Account(user.getUsername(), true);
    }

    /**
     * Sets a new password after a reset and signs out every device; returns a fresh session. An
     * account that never had a username (API token only) needs one now, or it could not sign in.
     */
    @Transactional
    Registration resetPassword(long userId, String username, String newPassword) {
        AppUser user = find(userId);
        if (user.getUsername() == null) {
            if (username == null) {
                throw new BusinessRuleException("Choose a username to sign in with");
            }
            changeUsername(new CurrentUser(userId, user.getDisplayName()), username);
        }
        user.changePasswordHash(passwords.hash(newPassword));
        sessions.revokeAll(userId, null);
        return new Registration(userId, user.getDisplayName(), sessions.create(userId));
    }

    /** Signs out this device; an API token has no session and stays valid. */
    @Transactional
    public void logout(CurrentUser current) {
        if (current.sessionId() != null) {
            sessions.revoke(current.sessionId());
        }
    }

    @Transactional
    public void logoutEverywhere(CurrentUser current) {
        sessions.revokeAll(current.id(), null);
    }

    static String normalizeUsername(String username) {
        String name = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(name).matches()) {
            throw new BusinessRuleException("A username has 3 to 30 letters, digits, dots, dashes or underscores");
        }
        return name;
    }

    private AppUser find(long userId) {
        return users.findById(userId).orElseThrow(() -> new NotFoundException("User " + userId + " does not exist"));
    }
}
