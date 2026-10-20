package dev.splitledger.security;

import dev.splitledger.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** With a registration code configured (public host), strangers cannot create accounts. */
@TestPropertySource(properties = "splitledger.registration-code=friends-only-2026")
class RegistrationCodeTest extends IntegrationTest {

    @Test
    void registrationNeedsTheConfiguredCode() {
        String body = "{\"displayName\": \"Ben\"}";

        assertThat(anonymous().post().uri("/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .hasStatus(403);
        assertThat(anonymous().post().uri("/users").header("X-Registration-Code", "guess")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .hasStatus(403);
        assertThat(anonymous().post().uri("/users").header("X-Registration-Code", "friends-only-2026")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .hasStatus(201);
    }
}
