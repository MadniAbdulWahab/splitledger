package dev.splitledger.expense;

import com.jayway.jsonpath.JsonPath;
import dev.splitledger.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExpenseCategoryApiTest extends IntegrationTest {

    @Test
    void categoryAndCreatorAreStoredAndReturned() {
        long groupId = createGroup("Flat");
        long anna = myMemberId(groupId);
        long ben = addMember(groupId, "Ben");

        // Anna enters an expense Ben paid: the payer and the person who added it differ.
        MvcTestResult created = post(groupId, UUID.randomUUID().toString(), ben, "REWE", "\"GROCERIES\"", anna, ben);
        assertThat(created).hasStatus(201).bodyJson()
                .hasPathSatisfying("$.category", v -> v.assertThat().isEqualTo("GROCERIES"))
                .hasPathSatisfying("$.createdBy", v -> v.assertThat().asNumber().isEqualTo((int) anna));
        long id = idOf(created);

        assertThat(mvc.get().uri("/groups/{g}/expenses/{e}", groupId, id)).bodyJson()
                .hasPathSatisfying("$.category", v -> v.assertThat().isEqualTo("GROCERIES"));
        assertThat(mvc.get().uri("/groups/{g}/expenses/search?q=rewe", groupId)).bodyJson()
                .hasPathSatisfying("$.items[0].category", v -> v.assertThat().isEqualTo("GROCERIES"));
    }

    @Test
    void withoutACategoryItIsNullAndUnknownOnesAreRefused() {
        long groupId = createGroup("Flat");
        long anna = myMemberId(groupId);
        long ben = addMember(groupId, "Ben");

        assertThat(post(groupId, UUID.randomUUID().toString(), anna, "Something", null, anna, ben)).hasStatus(201)
                .bodyJson().hasPathSatisfying("$.category", v -> v.assertThat().isNull());
        assertThat(post(groupId, UUID.randomUUID().toString(), anna, "Something", "\"LUXURY\"", anna, ben)).hasStatus(400);
    }

    @Test
    void searchFiltersByCategoryAndTreatsMissingAsOther() {
        long groupId = createGroup("Flat");
        long anna = myMemberId(groupId);
        long ben = addMember(groupId, "Ben");
        post(groupId, UUID.randomUUID().toString(), anna, "Lidl", "\"GROCERIES\"", anna, ben);
        post(groupId, UUID.randomUUID().toString(), anna, "Kino", "\"ENTERTAINMENT\"", anna, ben);
        post(groupId, UUID.randomUUID().toString(), anna, "Old one", null, anna, ben);

        assertThat(descriptions(groupId, "GROCERIES")).containsExactly("Lidl");
        assertThat(descriptions(groupId, "OTHER")).containsExactly("Old one");
        assertThat(mvc.get().uri("/groups/{g}/expenses/search?category=NOPE", groupId)).hasStatus(400);
    }

    @Test
    void replayWithTheSameKeyAndCategoryReturnsTheFirstExpense() {
        long groupId = createGroup("Flat");
        long anna = myMemberId(groupId);
        long ben = addMember(groupId, "Ben");
        String key = UUID.randomUUID().toString();

        long first = idOf(post(groupId, key, anna, "Pizza", "\"FOOD\"", anna, ben));
        MvcTestResult replay = post(groupId, key, anna, "Pizza", "\"FOOD\"", anna, ben);
        assertThat(replay).hasStatus(201).hasHeader("Idempotent-Replayed", "true");
        assertThat(idOf(replay)).isEqualTo(first);
        // Another category is another request body.
        assertThat(post(groupId, key, anna, "Pizza", "\"OTHER\"", anna, ben)).hasStatus(409);
    }

    @Test
    void anEditCanChangeTheCategoryAndRecordsWhoEdited() {
        long groupId = createGroup("Flat");
        long anna = myMemberId(groupId);
        long ben = addMember(groupId, "Ben");
        long id = idOf(post(groupId, UUID.randomUUID().toString(), anna, "Bahn", null, anna, ben));

        MvcTestResult edited = mvc.post().uri("/groups/{g}/expenses/{e}/correction", groupId, id)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(anna, "Bahn", "\"TRANSPORT\"", anna, ben))
                .exchange();
        assertThat(edited).hasStatus(201).bodyJson()
                .hasPathSatisfying("$.category", v -> v.assertThat().isEqualTo("TRANSPORT"))
                .hasPathSatisfying("$.createdBy", v -> v.assertThat().asNumber().isEqualTo((int) anna));
    }

    private List<String> descriptions(long groupId, String category) {
        return JsonPath.read(body(mvc.get().uri("/groups/{g}/expenses/search?category={c}", groupId, category).exchange()),
                "$.items[*].description");
    }

    private MvcTestResult post(long groupId, String key, long payer, String description, String categoryJson,
                               long... participants) {
        return mvc.post().uri("/groups/{id}/expenses", groupId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(payer, description, categoryJson, participants))
                .exchange();
    }

    private static String body(long payer, String description, String categoryJson, long... participants) {
        StringBuilder list = new StringBuilder();
        for (long participant : participants) {
            list.append(list.isEmpty() ? "" : ", ").append("{\"memberId\": ").append(participant).append('}');
        }
        return """
                {"payerId": %d, "amountCents": 2400, "description": "%s", "expenseDate": "2026-10-21",
                 "splitMode": "EQUAL", "participants": [%s]%s}
                """.formatted(payer, description, list, categoryJson == null ? "" : ", \"category\": " + categoryJson);
    }
}
