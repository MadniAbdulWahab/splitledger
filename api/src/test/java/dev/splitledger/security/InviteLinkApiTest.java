package dev.splitledger.security;

import com.jayway.jsonpath.JsonPath;
import dev.splitledger.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static dev.splitledger.security.PasswordLoginApiTest.uniqueUsername;
import static org.assertj.core.api.Assertions.assertThat;

/** A friend opens the link from WhatsApp, picks a username and password, and is in the group. */
class InviteLinkApiTest extends IntegrationTest {

    @Test
    void previewShowsWhoInvitedWhomWithoutAnAccount() {
        long groupId = createGroup("WG Kessenich");
        String code = inviteCode(groupId, "Ben");

        assertThat(anonymous().post().uri("/invitations/preview").contentType(MediaType.APPLICATION_JSON)
                .content(codeJson(code)))
                .hasStatusOk().bodyJson()
                .hasPathSatisfying("$.groupName", v -> v.assertThat().isEqualTo("WG Kessenich"))
                .hasPathSatisfying("$.memberName", v -> v.assertThat().isEqualTo("Ben"))
                .hasPathSatisfying("$.invitedByName", v -> v.assertThat().isEqualTo("Anna"));
        assertThat(anonymous().post().uri("/invitations/preview").contentType(MediaType.APPLICATION_JSON)
                .content(codeJson("inv_made-up"))).hasStatus(404);
    }

    @Test
    void signingUpThroughTheLinkCreatesTheAccountAndJoinsTheGroup() {
        long groupId = createGroup("Flat");
        String code = inviteCode(groupId, "Ben");
        String username = uniqueUsername("ben");

        MvcTestResult signup = signup(code, username, "correct horse");
        assertThat(signup).hasStatus(201);
        String token = JsonPath.read(body(signup), "$.token");

        assertThat(as(token).get().uri("/me")).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.displayName", v -> v.assertThat().isEqualTo("Ben"))
                .hasPathSatisfying("$.groups[0].groupId", v -> v.assertThat().asNumber().isEqualTo((int) groupId));
        assertThat(as(token).get().uri("/groups/{id}/balances", groupId)).hasStatusOk();
        assertThat(mvc.get().uri("/groups/{id}/members", groupId)).bodyJson()
                .hasPathSatisfying("$[1].joined", v -> v.assertThat().isEqualTo(true));
        assertThat(jdbc.queryForObject("select joined_at is not null from member where group_id = ? and display_name = 'Ben'",
                Boolean.class, groupId)).isTrue();

        // The link works once.
        assertThat(signup(code, uniqueUsername("eve"), "correct horse")).hasStatus(404);
    }

    @Test
    void aFailedSignupLeavesNoAccountAndTheLinkStillWorks() {
        long groupId = createGroup("Flat");
        String code = inviteCode(groupId, "Ben");
        long usersBefore = jdbc.queryForObject("select count(*) from app_user", Long.class);

        assertThat(signup(code, uniqueUsername("ben"), "short")).hasStatus(422);
        assertThat(signup("inv_unknown", uniqueUsername("ben"), "correct horse")).hasStatus(404);

        assertThat(jdbc.queryForObject("select count(*) from app_user", Long.class)).isEqualTo(usersBefore);
        assertThat(signup(code, uniqueUsername("ben"), "correct horse")).hasStatus(201);
    }

    @Test
    void aNewLinkReplacesTheOldOneUntilTheFriendHasJoined() {
        long groupId = createGroup("Flat");
        MvcTestResult added = addMemberWithInvite(groupId, "Ben");
        long benId = longAt(added, "$.member.id");
        String oldCode = JsonPath.read(body(added), "$.inviteCode");

        MvcTestResult reissued = mvc.post().uri("/groups/{g}/members/{m}/invitation", groupId, benId).exchange();
        assertThat(reissued).hasStatusOk();
        String newCode = JsonPath.read(body(reissued), "$.inviteCode");

        assertThat(signup(oldCode, uniqueUsername("ben"), "correct horse")).hasStatus(404);
        assertThat(signup(newCode, uniqueUsername("ben"), "correct horse")).hasStatus(201);
        assertThat(mvc.post().uri("/groups/{g}/members/{m}/invitation", groupId, benId)).hasStatus(409);
    }

    @Test
    void onlyMembersCanMakeNewLinks() {
        long groupId = createGroup("Flat");
        long benId = addMember(groupId, "Ben");

        assertThat(as(userService.register("Eve").token()).post()
                .uri("/groups/{g}/members/{m}/invitation", groupId, benId)).hasStatus(403);
        long otherGroup = createGroup("Other");
        assertThat(mvc.post().uri("/groups/{g}/members/{m}/invitation", otherGroup, benId)).hasStatus(404);
    }

    @Test
    void aTakenUsernameIsRefused() {
        long groupId = createGroup("Flat");
        String username = uniqueUsername("ben");
        assertThat(signup(inviteCode(groupId, "Ben"), username, "correct horse")).hasStatus(201);

        assertThat(signup(inviteCode(groupId, "Ben Two"), username, "correct horse")).hasStatus(409);
    }

    private String inviteCode(long groupId, String name) {
        return JsonPath.read(body(addMemberWithInvite(groupId, name)), "$.inviteCode");
    }

    private MvcTestResult signup(String code, String username, String password) {
        return anonymous().post().uri("/invitations/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"%s\", \"username\": \"%s\", \"password\": \"%s\"}".formatted(code, username, password))
                .exchange();
    }

    private static String codeJson(String code) {
        return "{\"code\": \"%s\"}".formatted(code);
    }
}
