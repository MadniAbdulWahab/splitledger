package dev.splitledger.security;

import dev.splitledger.common.BusinessRuleException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Passwords are hashed with bcrypt, which is deliberately slow (about 0.1 s per check), unlike the
 * SHA-256 used for random tokens: a password has little randomness, so every guess has to be
 * expensive. bcrypt only reads the first 72 bytes, so longer passwords are refused instead of
 * being cut off without a word.
 */
@Component
class Passwords {

    static final int MIN_LENGTH = 8;
    static final int MAX_BYTES = 72;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** Compared against when the username does not exist, so the answer takes as long either way. */
    private final String dummyHash = encoder.encode("not a real password, only for timing");

    String hash(String password) {
        if (password.length() < MIN_LENGTH) {
            throw new BusinessRuleException("The password needs at least " + MIN_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new BusinessRuleException("The password is too long (at most " + MAX_BYTES + " bytes)");
        }
        return encoder.encode(password);
    }

    /** {@code hash} may be null (unknown user, or no password set): then the check fails, just as slowly. */
    boolean matches(String password, String hash) {
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return false;
        }
        boolean ok = encoder.matches(password, hash != null ? hash : dummyHash);
        return ok && hash != null;
    }
}
