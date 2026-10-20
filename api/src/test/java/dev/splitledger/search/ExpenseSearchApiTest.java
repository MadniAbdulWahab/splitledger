package dev.splitledger.search;

import com.jayway.jsonpath.JsonPath;
import dev.splitledger.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExpenseSearchApiTest extends IntegrationTest {

    private long groupId;
    private long anna;
    private long ben;
    private long cem;

    @BeforeEach
    void setUpGroup() {
        groupId = createGroup("Search");
        anna = addMember(groupId, "Anna");
        ben = addMember(groupId, "Ben");
        cem = addMember(groupId, "Cem");
    }

    @Test
    void findsGermanWordForms() {
        long plural = expense(anna, 4200, "Einkäufe bei Edeka", null, "2026-03-02", anna, ben);
        long compound = expense(anna, 3100, "Getränke für die Party", null, "2026-03-05", anna, ben);

        assertThat(searchIds("q", "Einkauf")).containsExactly(plural);
        assertThat(searchIds("q", "Getränk")).containsExactly(compound);
    }

    @Test
    void toleratesTyposAndFindsWordsInsideCompounds() {
        long pizza = expense(ben, 2400, "Pizzeria Napoli", null, "2026-03-02", anna, ben);
        long lidl = expense(cem, 1850, "Wocheneinkauf Lidl", null, "2026-03-03", ben, cem);

        assertThat(searchIds("q", "Pizerria")).containsExactly(pizza);
        assertThat(searchIds("q", "Lidel")).containsExactly(lidl);
        // German stemming does not split compounds; the trigram index finds "einkauf" inside the word.
        assertThat(searchIds("q", "Einkauf")).containsExactly(lidl);
    }

    @Test
    void searchesNotesButRanksDescriptionHigher() {
        long inNotes = expense(anna, 1500, "Blumen", "Geschenk zum Geburtstag", "2026-03-02", anna, ben);
        long inDescription = expense(anna, 3000, "Geschenk für Jonas", null, "2026-03-03", anna, ben);

        assertThat(searchIds("q", "Geschenk")).containsExactly(inDescription, inNotes);
    }

    @Test
    void filtersByMemberDateAndAmount() {
        long annaPaid = expense(anna, 1000, "Einkauf Rewe", null, "2026-03-01", anna, ben);
        long cemTookPart = expense(ben, 5000, "Einkauf Aldi", null, "2026-03-15", ben, cem);
        long april = expense(ben, 2000, "Einkauf Penny", null, "2026-04-02", anna, ben);

        // member = payer or participant
        assertThat(searchIds("q", "Einkauf", "member", String.valueOf(cem))).containsExactly(cemTookPart);
        assertThat(searchIds("q", "Einkauf", "member", String.valueOf(anna))).containsExactlyInAnyOrder(annaPaid, april);
        assertThat(searchIds("q", "Einkauf", "from", "2026-03-01", "to", "2026-03-31"))
                .containsExactlyInAnyOrder(annaPaid, cemTookPart);
        assertThat(searchIds("q", "Einkauf", "min", "1500", "max", "4999")).containsExactly(april);
        // Filters also work without a search text; then the newest expense comes first.
        assertThat(searchIds("max", "2000")).containsExactly(april, annaPaid);
    }

    @Test
    void keysetPaginationReturnsEveryResultExactlyOnce() throws Exception {
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(expense(anna, 1000 + i, "Kino " + i, null, "2026-03-0" + (i + 1), anna, ben));
        }

        List<Long> seen = new ArrayList<>();
        String cursor = null;
        List<Integer> pageSizes = new ArrayList<>();
        do {
            MvcTestResult page = cursor == null
                    ? search("q", "Kino", "limit", "2")
                    : search("q", "Kino", "limit", "2", "cursor", cursor);
            List<Number> ids = JsonPath.read(body(page), "$.items[*].id");
            pageSizes.add(ids.size());
            ids.forEach(id -> seen.add(id.longValue()));
            cursor = JsonPath.read(body(page), "$.nextCursor");
        } while (cursor != null);

        assertThat(pageSizes).containsExactly(2, 2, 1);
        assertThat(seen).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(created);
    }

    @Test
    void neverReturnsExpensesOfOtherGroups() {
        long ours = expense(anna, 1000, "Einkauf Edeka", null, "2026-03-02", anna, ben);
        long otherGroup = createGroup("Neighbours");
        long dana = addMember(otherGroup, "Dana");
        createExpense(otherGroup, dana, 1000, "Einkauf Edeka", dana);

        assertThat(searchIds("q", "Edeka")).containsExactly(ours);
    }

    @Test
    void marksReversedExpenses() {
        long expenseId = expense(anna, 1000, "Doppelt erfasst", null, "2026-03-02", anna, ben);
        mvc.post().uri("/groups/{g}/expenses/{e}/reversal", groupId, expenseId).exchange();

        assertThat(search("q", "Doppelt")).bodyJson().extractingPath("$.items[0].reversed").isEqualTo(true);
    }

    @Test
    void rejectsBadParameters() {
        assertThat(search("cursor", "not-a-cursor")).hasStatus(400);
        assertThat(search("limit", "101")).hasStatus(400);
        assertThat(search("min", "-1")).hasStatus(400);
        assertThat(mvc.get().uri("/groups/{id}/expenses/search?q=x", Long.MAX_VALUE)).hasStatus(404);
    }

    private long expense(long payer, long amountCents, String description, String notes, String date, long... participants) {
        StringBuilder list = new StringBuilder();
        for (long participant : participants) {
            list.append(list.isEmpty() ? "" : ", ").append("{\"memberId\": ").append(participant).append('}');
        }
        MvcTestResult result = mvc.post().uri("/groups/{id}/expenses", groupId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"payerId": %d, "amountCents": %d, "description": "%s", "notes": %s,
                         "expenseDate": "%s", "splitMode": "EQUAL", "participants": [%s]}
                        """.formatted(payer, amountCents, description, notes == null ? "null" : "\"" + notes + "\"", date, list))
                .exchange();
        assertThat(result).hasStatus(201);
        return idOf(result);
    }

    private MvcTestResult search(String... params) {
        var request = mvc.get().uri("/groups/{id}/expenses/search", groupId);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return request.exchange();
    }

    private List<Long> searchIds(String... params) {
        MvcTestResult result = search(params);
        assertThat(result).hasStatusOk();
        List<Number> ids = JsonPath.read(body(result), "$.items[*].id");
        return ids.stream().map(Number::longValue).toList();
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
