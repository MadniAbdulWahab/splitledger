package dev.splitledger.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign-up is open to anyone, so one network may create at most {@code splitledger.signup-limit-per-hour}
 * accounts per hour: enough for friends behind one router, too few for a bot filling the database.
 * Only accounts actually created count, so a taken username or a short password costs nothing.
 * In memory, like {@link LoginThrottle}; a restart only resets the counters.
 */
@Component
class SignupThrottle {

    static final Duration WINDOW = Duration.ofHours(1);

    private final int limit;
    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Instant>> signups = new ConcurrentHashMap<>();

    @Autowired
    SignupThrottle(@Value("${splitledger.signup-limit-per-hour:5}") int limit) {
        this(limit, Clock.systemUTC());
    }

    /** For tests that move the clock. */
    SignupThrottle(int limit, Clock clock) {
        this.limit = limit;
        this.clock = clock;
    }

    /** Throws 429 when this address already created {@code limit} accounts within the last hour. */
    void check(String address) {
        Deque<Instant> recent = signups.get(address);
        if (recent == null) {
            return;
        }
        synchronized (recent) {
            dropOld(recent);
            if (recent.size() >= limit) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Too many new accounts from this network. Try again in an hour.");
            }
        }
    }

    void created(String address) {
        Deque<Instant> recent = signups.computeIfAbsent(address, a -> new ArrayDeque<>());
        synchronized (recent) {
            dropOld(recent);
            recent.addLast(clock.instant());
        }
        // Addresses without recent sign-ups are forgotten, so the map does not grow forever.
        signups.values().removeIf(d -> {
            synchronized (d) {
                dropOld(d);
                return d.isEmpty();
            }
        });
    }

    private void dropOld(Deque<Instant> recent) {
        Instant cutoff = clock.instant().minus(WINDOW);
        while (!recent.isEmpty() && !recent.peekFirst().isAfter(cutoff)) {
            recent.removeFirst();
        }
    }
}
