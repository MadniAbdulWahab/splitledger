package dev.splitledger.security;

import com.jayway.jsonpath.JsonPath;
import dev.splitledger.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With a registration code configured, strangers cannot create accounts. The same context runs
 * with a sign-up limit of 3 per network (the tests' own default is far higher).
 */
@TestPropertySource(properties = {"splitledger.registration-code=friends-only-2026", "splitledger.signup-limit-per-hour=3"})
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

    @Test
    void thePageIsToldThatTheCodeIsNeeded() {
        assertThat(anonymous().get().uri("/users/registration")).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.codeRequired", v -> v.assertThat().isEqualTo(true));
    }

    @Test
    void oneNetworkCreatesAtMostTheLimitPerHourAndFailuresDoNotCount() {
        String username = PasswordLoginApiTest.uniqueUsername("bot");
        assertThat(signUp("203.0.113.7", username)).hasStatus(201);
        assertThat(signUp("203.0.113.7", username)).as("taken username, nothing created").hasStatus(409);
        assertThat(signUp("203.0.113.7", PasswordLoginApiTest.uniqueUsername("bot"))).hasStatus(201);
        assertThat(signUp("203.0.113.7", PasswordLoginApiTest.uniqueUsername("bot"))).hasStatus(201);

        assertThat(signUp("203.0.113.7", PasswordLoginApiTest.uniqueUsername("bot"))).hasStatus(429);
        long groupId = createGroup("Flat");
        String code = JsonPath.read(body(addMemberWithInvite(groupId, "Ben")), "$.inviteCode");
        assertThat(anonymous().post().uri("/invitations/signup").with(from("203.0.113.7"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"%s\", \"username\": \"%s\", \"password\": \"correct horse\"}"
                        .formatted(code, PasswordLoginApiTest.uniqueUsername("ben"))))
                .as("invite links count too").hasStatus(429);
        assertThat(signUp("198.51.100.1", PasswordLoginApiTest.uniqueUsername("friend"))).as("another network").hasStatus(201);
    }

    private MvcTestResult signUp(String address, String username) {
        return anonymous().post().uri("/users").with(from(address)).header("X-Registration-Code", "friends-only-2026")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\": \"Bot\", \"username\": \"%s\", \"password\": \"correct horse\"}".formatted(username))
                .exchange();
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    @Test
    void anInviteLinkIsEnoughWithoutTheCode() {
        long groupId = createGroup("Flat");
        String code = JsonPath.read(body(addMemberWithInvite(groupId, "Ben")), "$.inviteCode");

        assertThat(anonymous().post().uri("/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\": \"Ben\", \"username\": \"ben-nocode\", \"password\": \"correct horse\"}"))
                .hasStatus(403);
        assertThat(anonymous().post().uri("/invitations/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"%s\", \"username\": \"%s\", \"password\": \"correct horse\"}"
                        .formatted(code, PasswordLoginApiTest.uniqueUsername("ben"))))
                .hasStatus(201);
    }
}
