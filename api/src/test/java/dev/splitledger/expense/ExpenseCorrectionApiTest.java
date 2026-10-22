package dev.splitledger.expense;

import com.jayway.jsonpath.JsonPath;
import dev.splitledger.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Delete and edit for users, reversals underneath: nothing is overwritten, the history keeps the
 * old version crossed out with the name of the member who changed it.
 */
class ExpenseCorrectionApiTest extends IntegrationTest {

    private long groupId;
    private long anna;
    private long ben;
    private MockMvcTester benClient;

    @BeforeEach
    void setUpGroupWithBen() {
        groupId = createGroup("Flat");
        anna = myMemberId(groupId);
        MvcTestResult added = addMemberWithInvite(groupId, "Ben");
        ben = longAt(added, "$.member.id");
        benClient = as(userService.register("Ben").token());
        assertThat(benClient.post().uri("/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"%s\"}".formatted((String) JsonPath.read(body(added), "$.inviteCode"))))
                .hasStatusOk();
    }

    @Test
    void anyMemberCanDeleteAndTheHistoryShowsWho() {
        long pizza = createExpense(groupId, anna, 3000, "Pizza", anna, ben);

        assertThat(benClient.post().uri("/groups/{g}/expenses/{e}/reversal", groupId, pizza)).hasStatusOk();

        assertThat(balances()).containsOnly(0);
        Map<String, Object> hit = history().getFirst();
        assertThat(hit).containsEntry("description", "Pizza").containsEntry("reversed", true)
                .containsEntry("change", "DELETED").containsEntry("changedBy", (int) ben);
        assertThat(hit.get("changedAt")).isNotNull();
        // Deleting twice is a conflict, not a second reversal.
        assertThat(mvc.post().uri("/groups/{g}/expenses/{e}/reversal", groupId, pizza)).hasStatus(409);
    }

    @Test
    void editReplacesTheExpenseInOneStepAndKeepsTheOldVersion() {
        long pizza = createExpense(groupId, anna, 3000, "Pizza", anna, ben);

        MvcTestResult edited = edit(pizza, "edit-1", 4000, "Pizza and drinks");

        assertThat(edited).hasStatus(201);
        assertThat(edited).hasHeader("Idempotent-Replayed", "false");
        long newId = idOf(edited);
        assertThat(newId).isNotEqualTo(pizza);
        // Only the new version counts: Anna paid 40,00, each owes 20,00.
        assertThat(balances()).containsExactlyInAnyOrder(2000, -2000);

        List<Map<String, Object>> history = history();
        assertThat(history).hasSize(2);
        assertThat(history.get(0)).containsEntry("id", (int) newId).containsEntry("reversed", false)
                .containsEntry("replacesExpenseId", (int) pizza);
        assertThat(history.get(0).get("change")).isNull();
        assertThat(history.get(1)).containsEntry("id", (int) pizza).containsEntry("reversed", true)
                .containsEntry("change", "EDITED").containsEntry("changedBy", (int) anna);
        // The ledger is still one valid chain: original, reversal, new version.
        assertThat(mvc.get().uri("/groups/{g}/verify", groupId)).bodyJson().extractingPath("$.valid").isEqualTo(true);
    }

    @Test
    void editIsIdempotentAndAnExpenseIsCorrectedOnlyOnce() {
        long pizza = createExpense(groupId, anna, 3000, "Pizza", anna, ben);
        long newId = idOf(edit(pizza, "edit-1", 4000, "Pizza and drinks"));

        // A retry after a timeout gets the same new version, no second edit.
        MvcTestResult replay = edit(pizza, "edit-1", 4000, "Pizza and drinks");
        assertThat(replay).hasStatus(201);
        assertThat(replay).hasHeader("Idempotent-Replayed", "true");
        assertThat(idOf(replay)).isEqualTo(newId);

        // The same key with another body, or another edit of the replaced version, is refused.
        assertThat(edit(pizza, "edit-1", 4500, "Pizza and drinks")).hasStatus(409);
        assertThat(edit(pizza, "edit-2", 4500, "Pizza and drinks")).hasStatus(409);
        assertThat(balances()).containsExactlyInAnyOrder(2000, -2000);

        // The new version itself can be edited again.
        assertThat(edit(newId, "edit-3", 5000, "Pizza, drinks, dessert")).hasStatus(201);
        assertThat(balances()).containsExactlyInAnyOrder(2500, -2500);
    }

    @Test
    void editFollowsTheSameRulesAsCreating() {
        long pizza = createExpense(groupId, anna, 3000, "Pizza", anna, ben);

        MvcTestResult payerOnly = mvc.post().uri("/groups/{g}/expenses/{e}/correction", groupId, pizza)
                .header("Idempotency-Key", "edit-payer-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"payerId": %d, "amountCents": 3000, "description": "Pizza", "expenseDate": "2026-10-21",
                         "splitMode": "EQUAL", "participants": [{"memberId": %d}]}
                        """.formatted(anna, anna))
                .exchange();

        assertThat(payerOnly).hasStatus(422);
        // Nothing happened: the original still counts and is not marked as changed.
        assertThat(balances()).containsExactlyInAnyOrder(1500, -1500);
        assertThat(history().getFirst()).containsEntry("reversed", false);
        assertThat(history().getFirst().get("change")).isNull();
    }

    @Test
    void strangersCannotDeleteOrEdit() {
        long pizza = createExpense(groupId, anna, 3000, "Pizza", anna, ben);
        MockMvcTester stranger = as(userService.register("Eve").token());

        assertThat(stranger.post().uri("/groups/{g}/expenses/{e}/reversal", groupId, pizza)).hasStatus(403);
        assertThat(stranger.post().uri("/groups/{g}/expenses/{e}/correction", groupId, pizza)
                .header("Idempotency-Key", "eve").contentType(MediaType.APPLICATION_JSON).content("{}")).hasStatus(403);
        assertThat(balances()).containsExactlyInAnyOrder(1500, -1500);
    }

    private MvcTestResult edit(long expenseId, String key, long amountCents, String description) {
        return mvc.post().uri("/groups/{g}/expenses/{e}/correction", groupId, expenseId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"payerId": %d, "amountCents": %d, "description": "%s", "expenseDate": "2026-10-21",
                         "splitMode": "EQUAL", "participants": [{"memberId": %d}, {"memberId": %d}]}
                        """.formatted(anna, amountCents, description, anna, ben))
                .exchange();
    }

    private List<Integer> balances() {
        return JsonPath.read(body(mvc.get().uri("/groups/{g}/balances", groupId).exchange()), "$.balances[*].balanceCents");
    }

    private List<Map<String, Object>> history() {
        return JsonPath.read(body(mvc.get().uri("/groups/{g}/expenses/search", groupId).exchange()), "$.items");
    }
}
