package dev.splitledger.security;

import com.jayway.jsonpath.JsonPath;
import dev.splitledger.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static dev.splitledger.security.PasswordLoginApiTest.uniqueUsername;
import static org.assertj.core.api.Assertions.assertThat;

/** Ben forgot his password; Anna, who is in a group with him, makes him a reset link. */
class PasswordResetApiTest extends IntegrationTest {

    private long groupId;
    private long benId;
    private String benUsername;
    private String benToken;

    @BeforeEach
    void benJoins() {
        groupId = createGroup("Flat");
        MvcTestResult added = addMemberWithInvite(groupId, "Ben");
        benId = longAt(added, "$.member.id");
        benUsername = uniqueUsername("ben");
        MvcTestResult signup = anonymous().post().uri("/invitations/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"%s\", \"username\": \"%s\", \"password\": \"forgotten one\"}"
                        .formatted(JsonPath.read(body(added), "$.inviteCode"), benUsername))
                .exchange();
        assertThat(signup).hasStatus(201);
        benToken = JsonPath.read(body(signup), "$.token");
    }

    @Test
    void aGroupmateMakesAOneTimeLinkThatSetsANewPassword() {
        String code = createReset(benId);
        assertThat(code).startsWith("rst_");

        assertThat(anonymous().post().uri("/password-resets/preview").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"%s\"}".formatted(code)))
                .hasStatusOk().bodyJson()
                .hasPathSatisfying("$.displayName", v -> v.assertThat().isEqualTo("Ben"))
                .hasPathSatisfying("$.username", v -> v.assertThat().isEqualTo(benUsername));

        MvcTestResult redeemed = redeem(code, "remembered one");
        assertThat(redeemed).hasStatusOk();
        assertThat(as(JsonPath.read(body(redeemed), "$.token")).get().uri("/me")).hasStatusOk();

        assertThat(as(benToken).get().uri("/me")).as("old sessions are signed out").hasStatus(401);
        assertThat(login(benUsername, "forgotten one")).hasStatus(401);
        assertThat(login(benUsername, "remembered one")).hasStatus(201);
        assertThat(redeem(code, "a third password")).as("the link works once").hasStatus(404);
    }

    @Test
    void expiredLinksDoNotWork() {
        String code = createReset(benId);
        jdbc.update("update password_reset set expires_at = now() - interval '1 second' where user_id = "
                + "(select user_id from member where id = ?)", benId);

        assertThat(redeem(code, "remembered one")).hasStatus(404);
    }

    @Test
    void onlyGroupmatesForJoinedMembersAndNotForYourself() {
        MvcTestResult stranger = as(userService.register("Eve").token()).post()
                .uri("/groups/{g}/members/{m}/password-reset", groupId, benId).exchange();
        assertThat(stranger).hasStatus(403);

        long notJoined = addMember(groupId, "Lucy");
        assertThat(mvc.post().uri("/groups/{g}/members/{m}/password-reset", groupId, notJoined)).hasStatus(422);
        assertThat(mvc.post().uri("/groups/{g}/members/{m}/password-reset", groupId, myMemberId(groupId))).hasStatus(422);
        assertThat(mvc.post().uri("/groups/{g}/members/{m}/password-reset", createGroup("Other"), benId)).hasStatus(404);
    }

    @Test
    void anAccountWithoutUsernameChoosesOneDuringTheReset() {
        // Anna (base class) has only an API token; Ben resets her password.
        MvcTestResult created = as(benToken).post()
                .uri("/groups/{g}/members/{m}/password-reset", groupId, myMemberId(groupId)).exchange();
        assertThat(created).hasStatus(201);
        String code = JsonPath.read(body(created), "$.resetCode");

        assertThat(redeem(code, "anna's password")).hasStatus(422);
        String username = uniqueUsername("anna");
        assertThat(anonymous().post().uri("/password-resets").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"%s\", \"username\": \"%s\", \"newPassword\": \"anna's password\"}".formatted(code, username)))
                .hasStatusOk();
        assertThat(login(username, "anna's password")).hasStatus(201);
    }

    private String createReset(long memberId) {
        MvcTestResult created = mvc.post().uri("/groups/{g}/members/{m}/password-reset", groupId, memberId).exchange();
        assertThat(created).hasStatus(201);
        return JsonPath.read(body(created), "$.resetCode");
    }

    private MvcTestResult redeem(String code, String newPassword) {
        return anonymous().post().uri("/password-resets").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"%s\", \"newPassword\": \"%s\"}".formatted(code, newPassword))
                .exchange();
    }

    private MvcTestResult login(String username, String password) {
        return anonymous().post().uri("/sessions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\": \"%s\", \"password\": \"%s\"}".formatted(username, password))
                .exchange();
    }
}
