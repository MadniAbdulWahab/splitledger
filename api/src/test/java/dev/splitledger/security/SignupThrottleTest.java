package dev.splitledger.security;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignupThrottleTest {

    /** A clock the test moves forward by hand. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-25T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    @Test
    void allowsTheLimitPerAddressWithinAnHourThenFreesUpAgain() {
        TestClock clock = new TestClock();
        SignupThrottle throttle = new SignupThrottle(2, clock);

        throttle.check("203.0.113.7");
        throttle.created("203.0.113.7");
        clock.advance(Duration.ofMinutes(30));
        throttle.created("203.0.113.7");

        assertThatThrownBy(() -> throttle.check("203.0.113.7"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("429");
        assertThatCode(() -> throttle.check("198.51.100.1")).as("another network").doesNotThrowAnyException();

        // An hour after the first sign-up one slot is free again.
        clock.advance(Duration.ofMinutes(31));
        assertThatCode(() -> throttle.check("203.0.113.7")).doesNotThrowAnyException();
    }

    @Test
    void checkingAloneNeverUsesASlot() {
        SignupThrottle throttle = new SignupThrottle(1, new TestClock());
        for (int i = 0; i < 10; i++) {
            throttle.check("203.0.113.7"); // e.g. a taken username: nothing was created
        }
        throttle.created("203.0.113.7");
        assertThatThrownBy(() -> throttle.check("203.0.113.7")).isInstanceOf(ResponseStatusException.class);
    }
}
