package dev.splitledger.security;

import com.jayway.jsonpath.JsonPath;
import dev.splitledger.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordLoginApiTest extends IntegrationTest {

    @Test
    void registerWithPasswordThenSignInOnAnotherDevice() {
        String username = uniqueUsername("Ben");
        String firstDevice = registerWithPassword("Ben", username, "correct horse");

        assertThat(as(firstDevice).get().uri("/me")).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.username", v -> v.assertThat().isEqualTo(username.toLowerCase()))
                .hasPathSatisfying("$.hasPassword", v -> v.assertThat().isEqualTo(true));

        // Case does not matter for the username, spaces around it neither.
        String secondDevice = login(" " + username.toUpperCase() + " ", "correct horse");
        assertThat(secondDevice).startsWith("slt_").isNotEqualTo(firstDevice);
        assertThat(as(secondDevice).get().uri("/me")).hasStatusOk();
        // Only hashes are stored.
        assertThat(jdbc.queryForObject("select count(*) from user_session where token_hash = ?", Long.class, secondDevice)).isZero();
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameAnswer() {
        String username = uniqueUsername("Ben");
        registerWithPassword("Ben", username, "correct horse");

        MvcTestResult wrong = loginResult(username, "wrong horse");
        MvcTestResult unknown = loginResult(uniqueUsername("Nobody"), "correct horse");

        assertThat(wrong).hasStatus(401);
        assertThat(unknown).hasStatus(401);
        assertThat((String) JsonPath.read(body(wrong), "$.detail")).isEqualTo(JsonPath.read(body(unknown), "$.detail"));
    }

    @Test
    void fiveWrongPasswordsLockTheUsernameForAMinute() {
        String username = uniqueUsername("Ben");
        registerWithPassword("Ben", username, "correct horse");

        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            assertThat(loginResult(username, "guess " + i)).hasStatus(401);
        }
        // Even the right password is refused while the lock lasts.
        assertThat(loginResult(username, "correct horse")).hasStatus(429);
    }

    @Test
    void signingOutRevokesOnlyThisDeviceAndEverywhereRevokesAll() {
        String username = uniqueUsername("Ben");
        String phone = registerWithPassword("Ben", username, "correct horse");
        String laptop = login(username, "correct horse");
        String tablet = login(username, "correct horse");

        assertThat(as(phone).post().uri("/logout")).hasStatus(204);
        assertThat(as(phone).get().uri("/me")).hasStatus(401);
        assertThat(as(laptop).get().uri("/me")).hasStatusOk();

        assertThat(as(laptop).post().uri("/me/sessions/revocation")).hasStatus(204);
        assertThat(as(laptop).get().uri("/me")).hasStatus(401);
        assertThat(as(tablet).get().uri("/me")).hasStatus(401);
    }

    @Test
    void expiredSessionsNoLongerWork() {
        String username = uniqueUsername("Ben");
        String token = registerWithPassword("Ben", username, "correct horse");
        jdbc.update("update user_session set expires_at = now() - interval '1 second' where user_id = "
                + "(select id from app_user where username = ?)", username.toLowerCase());

        assertThat(as(token).get().uri("/me")).hasStatus(401);
    }

    @Test
    void apiTokenAccountsKeepWorkingAndCanMoveToAPassword() {
        // "Anna" from the base class has only an API token.
        assertThat(mvc.get().uri("/me")).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.hasPassword", v -> v.assertThat().isEqualTo(false));
        String username = uniqueUsername("Anna");

        assertThat(mvc.put().uri("/me/username").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\": \"%s\"}".formatted(username))).hasStatusOk();
        // No current password needed the first time.
        assertThat(mvc.put().uri("/me/password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\": \"anna's new password\"}")).hasStatusOk();

        assertThat(as(login(username, "anna's new password")).get().uri("/me")).hasStatusOk();
        assertThat(mvc.get().uri("/me")).as("the API token still works").hasStatusOk();
    }

    @Test
    void changingThePasswordNeedsTheOldOneAndSignsOutOtherDevices() {
        String username = uniqueUsername("Ben");
        String phone = registerWithPassword("Ben", username, "correct horse");
        String laptop = login(username, "correct horse");

        assertThat(as(phone).put().uri("/me/password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\": \"wrong\", \"newPassword\": \"battery staple\"}")).hasStatus(422);
        assertThat(as(phone).put().uri("/me/password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\": \"correct horse\", \"newPassword\": \"battery staple\"}")).hasStatusOk();

        assertThat(as(phone).get().uri("/me")).as("this device stays signed in").hasStatusOk();
        assertThat(as(laptop).get().uri("/me")).hasStatus(401);
        assertThat(loginResult(username, "correct horse")).hasStatus(401);
        assertThat(loginResult(username, "battery staple")).hasStatus(201);
    }

    @Test
    void usernamesAreUniqueAndPasswordsHaveLimits() {
        String username = uniqueUsername("Ben");
        registerWithPassword("Ben", username, "correct horse");

        assertThat(registerResult("Other Ben", username.toUpperCase(), "another password")).hasStatus(409);
        assertThat(registerResult("Ben", uniqueUsername("Ben"), "short")).hasStatus(422);
        assertThat(registerResult("Ben", uniqueUsername("Ben"), "ä".repeat(37))).as("74 bytes").hasStatus(422);
        assertThat(registerResult("Ben", "no spaces allowed", "correct horse")).hasStatus(422);
        assertThat(anonymous().post().uri("/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\": \"Ben\", \"username\": \"%s\"}".formatted(uniqueUsername("Ben")))).hasStatus(400);
    }

    static String uniqueUsername(String name) {
        return name + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String registerWithPassword(String displayName, String username, String password) {
        MvcTestResult result = registerResult(displayName, username, password);
        assertThat(result).hasStatus(201);
        return JsonPath.read(body(result), "$.token");
    }

    private MvcTestResult registerResult(String displayName, String username, String password) {
        return anonymous().post().uri("/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\": \"%s\", \"username\": \"%s\", \"password\": \"%s\"}"
                        .formatted(displayName, username, password))
                .exchange();
    }

    private String login(String username, String password) {
        MvcTestResult result = loginResult(username, password);
        assertThat(result).hasStatus(201);
        return JsonPath.read(body(result), "$.token");
    }

    private MvcTestResult loginResult(String username, String password) {
        return anonymous().post().uri("/sessions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\": \"%s\", \"password\": \"%s\"}".formatted(username, password))
                .exchange();
    }
}
