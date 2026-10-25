package dev.splitledger.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * After {@link #MAX_FAILURES} wrong passwords in a row a username is locked for {@link #LOCK}, so
 * nobody can try thousands of passwords against a friend's account. In memory is enough for one
 * API instance; a restart only resets the counters.
 */
@Component
class LoginThrottle {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK = Duration.ofMinutes(1);

    private record Failures(int count, Instant lockedUntil) {
    }

    private final ConcurrentHashMap<String, Failures> failures = new ConcurrentHashMap<>();

    boolean isLocked(String username) {
        Failures f = failures.get(username);
        return f != null && f.lockedUntil() != null && f.lockedUntil().isAfter(Instant.now());
    }

    void failed(String username) {
        failures.compute(username, (name, f) -> {
            int count = (f == null || f.lockedUntil() != null ? 0 : f.count()) + 1;
            return new Failures(count, count >= MAX_FAILURES ? Instant.now().plus(LOCK) : null);
        });
    }

    void succeeded(String username) {
        failures.remove(username);
    }
}
