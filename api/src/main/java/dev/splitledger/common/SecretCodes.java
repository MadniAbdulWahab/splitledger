package dev.splitledger.common;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Random secrets (API tokens, invite codes). Only their SHA-256 is stored: a fast hash is enough
 * here because the secrets have 256 bits of randomness and cannot be guessed, unlike passwords,
 * which need a slow hash such as bcrypt.
 */
public final class SecretCodes {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecretCodes() {
    }

    /** E.g. "slt_3q2+..." for tokens; the prefix makes leaked tokens easy to recognize and scan for. */
    public static String generate(String prefix) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String secret) {
        return Sha256.hex(secret);
    }
}
