package dev.splitledger.insights;

import com.jayway.jsonpath.JsonPath;
import dev.splitledger.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InsightsApiTest extends IntegrationTest {

    private final YearMonth thisMonth = YearMonth.now(InsightsService.GROUP_TIME_ZONE);
    private long groupId;
    private long anna;
    private long ben;

    @BeforeEach
    void group() {
        groupId = createGroup("Flat");
        anna = myMemberId(groupId);
        ben = addMember(groupId, "Ben");
    }

    @Test
    void totalsPerMonthWithMyShareCountingOnlyTheWindow() {
        expense(anna, 3000, thisMonth.atDay(1), "GROCERIES");                 // my share 15,00
        expense(ben, 1001, thisMonth.atEndOfMonth(), "FOOD");                // my share 5,01
        expense(anna, 2000, thisMonth.minusMonths(2).atDay(15), "RENT");      // two months ago
        expense(anna, 9900, thisMonth.minusMonths(6).atDay(1), "TRAVEL");     // outside a 6-month window

        String body = insights(6);
        List<Map<String, Object>> months = JsonPath.read(body, "$.months");
        assertThat(months).hasSize(6);
        assertThat(months.getFirst().get("month")).isEqualTo(thisMonth.minusMonths(5).toString());
        assertThat(months.getLast()).containsEntry("month", thisMonth.toString()).containsEntry("totalCents", 4001);
        assertThat(months.get(3)).containsEntry("totalCents", 2000).containsEntry("myShareCents", 1000);
        assertThat(months.get(1)).containsEntry("totalCents", 0);
        assertThat((Integer) JsonPath.read(body, "$.totalCents")).isEqualTo(6001);
        // Anna's shares: 1500 + 501 (the odd cent goes to the lower member id) + 1000.
        assertThat((Integer) JsonPath.read(body, "$.myShareCents")).isEqualTo(3001);
        assertThat((String) JsonPath.read(body, "$.from")).isEqualTo(thisMonth.minusMonths(5).atDay(1).toString());
    }

    @Test
    void categoriesAndPayersLargestFirstWithMissingCategoryAsOther() {
        expense(anna, 3000, thisMonth.atDay(2), "GROCERIES");
        expense(ben, 1000, thisMonth.atDay(3), "GROCERIES");
        expense(ben, 5000, thisMonth.atDay(4), null);

        String body = insights(1);
        assertThat((List<String>) JsonPath.read(body, "$.categories[*].category")).containsExactly("OTHER", "GROCERIES");
        assertThat((Integer) JsonPath.read(body, "$.categories[1].totalCents")).isEqualTo(4000);
        assertThat((Integer) JsonPath.read(body, "$.categories[1].expenses")).isEqualTo(2);
        assertThat((List<Integer>) JsonPath.read(body, "$.payers[*].memberId")).containsExactly((int) ben, (int) anna);
        assertThat((List<Integer>) JsonPath.read(body, "$.payers[*].paidCents")).containsExactly(6000, 3000);
    }

    @Test
    void deletedExpensesAndOldVersionsOfEditsDoNotCount() {
        long deleted = expense(anna, 3000, thisMonth.atDay(2), "FOOD");
        long edited = expense(anna, 1000, thisMonth.atDay(3), "FOOD");
        assertThat(mvc.post().uri("/groups/{g}/expenses/{e}/reversal", groupId, deleted)).hasStatusOk();
        assertThat(mvc.post().uri("/groups/{g}/expenses/{e}/correction", groupId, edited)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(anna, 1200, thisMonth.atDay(3), "FOOD"))).hasStatus(201);

        assertThat((Integer) JsonPath.read(insights(1), "$.totalCents")).isEqualTo(1200);
    }

    @Test
    void monthsAreLimitedAndStrangersSeeNothing() {
        assertThat(mvc.get().uri("/groups/{g}/insights?months=0", groupId)).hasStatus(400);
        assertThat(mvc.get().uri("/groups/{g}/insights?months=25", groupId)).hasStatus(400);
        assertThat(as(userService.register("Eve").token()).get().uri("/groups/{g}/insights", groupId)).hasStatus(403);
    }

    private String insights(int months) {
        MvcTestResult result = mvc.get().uri("/groups/{g}/insights?months={m}", groupId, months).exchange();
        assertThat(result).hasStatusOk();
        return body(result);
    }

    private long expense(long payer, long cents, LocalDate date, String category) {
        MvcTestResult result = mvc.post().uri("/groups/{g}/expenses", groupId)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(payer, cents, date, category))
                .exchange();
        assertThat(result).hasStatus(201);
        return idOf(result);
    }

    private String body(long payer, long cents, LocalDate date, String category) {
        return """
                {"payerId": %d, "amountCents": %d, "description": "Something", "expenseDate": "%s",
                 "splitMode": "EQUAL", "participants": [{"memberId": %d}, {"memberId": %d}]%s}
                """.formatted(payer, cents, date, anna, ben, category == null ? "" : ", \"category\": \"" + category + "\"");
    }
}
