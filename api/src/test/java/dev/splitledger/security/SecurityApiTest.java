package dev.splitledger.security;

import dev.splitledger.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityApiTest extends IntegrationTest {

    @Test
    void requestsWithoutValidTokenAreRejected() {
        long groupId = createGroup("Private");

        assertThat(anonymous().get().uri("/groups/{id}", groupId))
                .hasStatus(401).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(anonymous().get().uri("/me")).hasStatus(401);
        assertThat(as("slt_not-a-real-token").get().uri("/me")).hasStatus(401);
        assertThat(anonymous().post().uri("/groups").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .hasStatus(401);
    }

    @Test
    void healthAndApiDocsArePublic() {
        assertThat(anonymous().get().uri("/actuator/health")).hasStatusOk();
        assertThat(anonymous().get().uri("/v3/api-docs")).hasStatusOk();
    }

    @Test
    void webPageIsPublicButTheApiIsNotAndEveryResponseCarriesTheContentSecurityPolicy() {
        // The built page is only in the Docker image; here the request must at least not need a token.
        assertThat(anonymous().get().uri("/").exchange().getResponse().getStatus()).isNotEqualTo(401);
        assertThat(anonymous().get().uri("/assets/app.js").exchange().getResponse().getStatus()).isNotEqualTo(401);
        assertThat(anonymous().get().uri("/me")).hasStatus(401);
        // A path that tries to climb out of the public folder never reaches a controller: Spring
        // Security's firewall rejects it before any rule is applied.
        assertThat(anonymous().get().uri("/assets/../me")).hasStatus(400);

        String csp = mvc.get().uri("/me").exchange().getResponse().getHeader("Content-Security-Policy");
        assertThat(csp).contains("script-src 'self'").contains("frame-ancestors 'none'").doesNotContain("unsafe-eval");
    }

    @Test
    void registrationReturnsATokenThatWorksOnce() {
        MvcTestResult registration = anonymous().post().uri("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\": \"Ben\"}")
                .exchange();

        assertThat(registration).hasStatus(201);
        String token = com.jayway.jsonpath.JsonPath.read(body(registration), "$.token");
        assertThat(token).startsWith("slt_");
        assertThat(as(token).get().uri("/me")).hasStatusOk().bodyJson().extractingPath("$.displayName").isEqualTo("Ben");

        // Only the hash is stored.
        assertThat(jdbc.queryForObject("select count(*) from app_user where token_hash = ?", Long.class, token)).isZero();
    }

    @Test
    void strangersCannotReachAnyGroupEndpoint() {
        long groupId = createGroup("Private");
        long anna = myMemberId(groupId);
        long expenseId = createExpense(groupId, anna, 1000, "Secret dinner", anna);
        MockMvcTester stranger = as(userService.register("Eve").token());

        String[] getPaths = {"/groups/{g}", "/groups/{g}/members", "/groups/{g}/balances", "/groups/{g}/verify",
                "/groups/{g}/expenses/search?q=dinner", "/groups/{g}/expenses/" + expenseId,
                "/groups/{g}/settlement", "/groups/{g}/bank-transactions"};
        for (String path : getPaths) {
            assertThat(stranger.get().uri(path, groupId)).as(path).hasStatus(403);
        }
        assertThat(stranger.post().uri("/groups/{g}/expenses/{e}/reversal", groupId, expenseId)).hasStatus(403);
        assertThat(stranger.post().uri("/groups/{g}/members", groupId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\": \"Eve\"}")).hasStatus(403);
        assertThat(stranger.get().uri("/me")).bodyJson().extractingPath("$.groups").asArray().isEmpty();
    }

    @Test
    void invitationLinksAFriendsAccountExactlyOnce() {
        long groupId = createGroup("Flat");
        String inviteCode = com.jayway.jsonpath.JsonPath.read(body(addMemberWithInvite(groupId, "Ben")), "$.inviteCode");
        MockMvcTester ben = as(userService.register("Ben").token());

        assertThat(ben.get().uri("/groups/{id}", groupId)).hasStatus(403);
        MvcTestResult accepted = ben.post().uri("/invitations/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"%s\"}".formatted(inviteCode))
                .exchange();

        assertThat(accepted).hasStatusOk().bodyJson().extractingPath("$.displayName").isEqualTo("Ben");
        assertThat(ben.get().uri("/groups/{id}", groupId)).hasStatusOk();
        assertThat(ben.get().uri("/me")).bodyJson().extractingPath("$.groups[0].groupId").asNumber().isEqualTo((int) groupId);
        assertThat(mvc.get().uri("/groups/{id}/members", groupId))
                .bodyJson().extractingPath("$[1].joined").isEqualTo(true);

        MockMvcTester eve = as(userService.register("Eve").token());
        assertThat(eve.post().uri("/invitations/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"%s\"}".formatted(inviteCode))).hasStatus(404);
    }
}
