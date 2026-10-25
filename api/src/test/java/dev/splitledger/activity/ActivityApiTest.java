package dev.splitledger.activity;

import com.jayway.jsonpath.JsonPath;
import dev.splitledger.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityApiTest extends IntegrationTest {

    @Test
    void everythingThatHappensInTheGroupAppearsNewestFirstWithWhoDidIt() {
        long groupId = createGroup("Flat");
        long anna = myMemberId(groupId);
        MvcTestResult invite = addMemberWithInvite(groupId, "Ben");
        long ben = longAt(invite, "$.member.id");
        MockMvcTester benClient = as(signUp(JsonPath.read(body(invite), "$.inviteCode")));

        long pizza = createExpense(groupId, anna, 2400, "Pizza", anna, ben);
        long kino = createExpense(groupId, anna, 1600, "Kino", anna, ben);
        assertThat(benClient.post().uri("/groups/{g}/expenses/{e}/reversal", groupId, kino)).hasStatusOk();
        assertThat(mvc.post().uri("/groups/{g}/expenses/{e}/correction", groupId, pizza)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"payerId": %d, "amountCents": 2600, "description": "Pizza", "expenseDate": "2026-10-21",
                         "splitMode": "EQUAL", "participants": [{"memberId": %d}, {"memberId": %d}]}
                        """.formatted(anna, anna, ben))).hasStatus(201);
        MvcTestResult payment = benClient.post().uri("/groups/{g}/payments", groupId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromMemberId\": %d, \"toMemberId\": %d, \"amountCents\": 1300}".formatted(ben, anna))
                .exchange();
        assertThat(payment).hasStatus(201);
        assertThat(mvc.post().uri("/groups/{g}/payments/{p}/reversal", groupId, idOf(payment))).hasStatusOk();
        assertThat(benClient.post().uri("/groups/{g}/members/{m}/password-reset", groupId, anna)).hasStatus(201);

        String feed = body(mvc.get().uri("/groups/{g}/activity", groupId).exchange());
        assertThat((List<String>) JsonPath.read(feed, "$.items[*].kind")).containsExactly(
                "PASSWORD_RESET", "PAYMENT_UNDONE", "PAYMENT_RECORDED", "EXPENSE_EDITED", "EXPENSE_DELETED",
                "EXPENSE_ADDED", "EXPENSE_ADDED", "MEMBER_JOINED", "MEMBER_ADDED", "GROUP_CREATED");
        assertThat((List<Integer>) JsonPath.read(feed, "$.items[*].actorMemberId")).containsExactly(
                (int) ben, (int) anna, (int) ben, (int) anna, (int) ben, (int) anna, (int) anna, (int) ben, (int) anna, (int) anna);
        // The edit shows the new version, the deletion what was deleted, the payment who paid whom.
        assertThat((Integer) JsonPath.read(feed, "$.items[3].amountCents")).isEqualTo(2600);
        assertThat((String) JsonPath.read(feed, "$.items[4].description")).isEqualTo("Kino");
        assertThat((Integer) JsonPath.read(feed, "$.items[2].fromMemberId")).isEqualTo((int) ben);
        assertThat((Integer) JsonPath.read(feed, "$.items[0].memberId")).as("whose password").isEqualTo((int) anna);
        assertThat((String) JsonPath.read(feed, "$.items[9].description")).isEqualTo("Flat");
    }

    @Test
    void pagesFollowEachOtherWithoutGapsOrRepeats() {
        long groupId = createGroup("Busy");
        long anna = myMemberId(groupId);
        long ben = addMember(groupId, "Ben");
        for (int i = 0; i < 7; i++) {
            createExpense(groupId, anna, 100 + i, "Item " + i, anna, ben);
        }

        List<String> ids = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String page = body(mvc.get().uri("/groups/{g}/activity?limit=3" + (cursor == null ? "" : "&cursor=" + cursor), groupId)
                    .exchange());
            ids.addAll(JsonPath.read(page, "$.items[*].id"));
            cursor = JsonPath.read(page, "$.nextCursor");
            pages++;
        } while (cursor != null);

        // 7 expenses, Ben added, the group created.
        assertThat(ids).hasSize(9).doesNotHaveDuplicates();
        assertThat(pages).isEqualTo(3);
        assertThat(mvc.get().uri("/groups/{g}/activity?cursor=garbage", groupId)).hasStatus(400);
    }

    @Test
    void strangersSeeNoActivity() {
        long groupId = createGroup("Private");
        assertThat(as(userService.register("Eve").token()).get().uri("/groups/{g}/activity", groupId)).hasStatus(403);
    }

    private String signUp(String code) {
        MvcTestResult result = anonymous().post().uri("/invitations/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"%s\", \"username\": \"ben-%s\", \"password\": \"correct horse\"}"
                        .formatted(code, UUID.randomUUID().toString().substring(0, 8)))
                .exchange();
        assertThat(result).hasStatus(201);
        return JsonPath.read(body(result), "$.token");
    }
}
