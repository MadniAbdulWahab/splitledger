package dev.splitledger.security;

import dev.splitledger.common.SecretCodes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class UserService {

    static final String TOKEN_PREFIX = "slt_";

    private final AppUserRepository users;

    UserService(AppUserRepository users) {
        this.users = users;
    }

    /** The token is returned exactly once; afterwards only its hash exists. */
    public record Registration(long id, String displayName, String token) {
    }

    @Transactional
    public Registration register(String displayName) {
        String token = SecretCodes.generate(TOKEN_PREFIX);
        AppUser user = users.save(new AppUser(displayName.strip(), SecretCodes.hash(token)));
        return new Registration(user.getId(), user.getDisplayName(), token);
    }

    @Transactional(readOnly = true)
    Optional<CurrentUser> authenticate(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        return users.findByTokenHash(SecretCodes.hash(token))
                .map(user -> new CurrentUser(user.getId(), user.getDisplayName()));
    }
}
