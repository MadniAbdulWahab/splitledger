package dev.splitledger.expense;

import dev.splitledger.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

class ExpenseApiTest extends IntegrationTest {

    private long groupId;
    private long anna;
    private long ben;
    private long cem;

    @BeforeEach
    void setUpGroup() {
        groupId = createGroup("Berlin trip");
        anna = myMemberId(groupId);
        ben = addMember(groupId, "Ben");
        cem = addMember(groupId, "Cem");
    }

    @Test
    void equalSplitWritesBalancedLedgerAndBalances() {
        MvcTestResult result = postExpense("key-1", equalSplit(anna, 1000, "Groceries"));

        assertThat(result).hasStatus(201).hasHeader("Idempotent-Replayed", "false");
        assertThat(result.getResponse().getHeader("Location")).endsWith("/groups/" + groupId + "/expenses/" + idOf(result));
        assertThat(result).bodyJson().extractingPath("$.shares[*].amountCents").asArray().containsExactly(334, 333, 333);

        long ledgerSum = jdbc.queryForObject(
                "select sum(amount_cents) from ledger_entry where kind = 'EXPENSE' and reference_id = ?", Long.class, idOf(result));
        assertThat(ledgerSum).isZero();

        // Anna paid 10.00 and owes 3.34 herself: +6.66; Ben and Cem owe 3.33 each.
        MvcTestResult balances = mvc.get().uri("/groups/{id}/balances", groupId).exchange();
        assertThat(balances).bodyJson().extractingPath("$.balances[*].balanceCents").asArray().containsExactly(666, -333, -333);
    }

    @Test
    void retryWithSameKeyAndBodyReturnsTheOriginalExpense() {
        String body = equalSplit(anna, 2500, "Pizza");
        MvcTestResult first = postExpense("retry-key", body);
        MvcTestResult retry = postExpense("retry-key", body);

        assertThat(retry).hasStatus(201).hasHeader("Idempotent-Replayed", "true");
        assertThat(idOf(retry)).isEqualTo(idOf(first));
        assertThat(retry.getResponse().getContentAsByteArray()).isEqualTo(first.getResponse().getContentAsByteArray());
        assertThat(countExpenses()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsAConflict() {
        postExpense("reused-key", equalSplit(anna, 2500, "Pizza"));

        MvcTestResult result = postExpense("reused-key", equalSplit(anna, 2600, "Pizza"));

        assertThat(result).hasStatus(409).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(countExpenses()).isEqualTo(1);
    }

    @Test
    void sameKeyInAnotherGroupIsIndependent() {
        long otherGroup = createGroup("Other");
        long dana = addMember(otherGroup, "Dana");
        postExpense("shared-key", equalSplit(anna, 500, "Coffee"));

        MvcTestResult result = mvc.post().uri("/groups/{id}/expenses", otherGroup)
                .header("Idempotency-Key", "shared-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(expenseJson(dana, 500, "Coffee", "EQUAL",
                        "[{\"memberId\": %d}, {\"memberId\": %d}]".formatted(dana, myMemberId(otherGroup))))
                .exchange();

        assertThat(result).hasStatus(201).hasHeader("Idempotent-Replayed", "false");
    }

    @Test
    void missingIdempotencyKeyIsABadRequest() {
        MvcTestResult result = mvc.post().uri("/groups/{id}/expenses", groupId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(equalSplit(anna, 1000, "No key"))
                .exchange();

        assertThat(result).hasStatus(400).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void exactAndSharesSplits() {
        MvcTestResult exact = postExpense("exact", expenseJson(ben, 1000, "Tickets", "EXACT",
                "[{\"memberId\": %d, \"value\": 700}, {\"memberId\": %d, \"value\": 300}]".formatted(ben, cem)));
        MvcTestResult shares = postExpense("shares", expenseJson(cem, 900, "Dinner", "SHARES",
                "[{\"memberId\": %d, \"value\": 2}, {\"memberId\": %d, \"value\": 1}]".formatted(anna, cem)));

        assertThat(exact).hasStatus(201).bodyJson().extractingPath("$.shares[*].amountCents").asArray().containsExactly(700, 300);
        assertThat(shares).hasStatus(201).bodyJson().extractingPath("$.shares[*].shareUnits").asArray().containsExactly(2, 1);
        // Anna -600, Ben +1000-700 = +300, Cem -300+900-300 = +300
        assertThat(mvc.get().uri("/groups/{id}/balances", groupId))
                .bodyJson().extractingPath("$.balances[*].balanceCents").asArray().containsExactly(-600, 300, 300);
    }

    @Test
    void exactAmountsThatDoNotAddUpAreRejected() {
        MvcTestResult result = postExpense("bad-exact", expenseJson(ben, 1000, "Tickets", "EXACT",
                "[{\"memberId\": %d, \"value\": 700}]".formatted(ben)));

        assertThat(result).hasStatus(422);
        assertThat(countExpenses()).isZero();
    }

    @Test
    void anExpenseOnlyThePayerSharesIsRejected() {
        MvcTestResult result = postExpense("self", expenseJson(anna, 1000, "Just me", "EQUAL",
                "[{\"memberId\": %d}]".formatted(anna)));

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("Only the payer takes part");
        assertThat(countExpenses()).isZero();
    }

    @Test
    void participantFromAnotherGroupIsRejected() {
        long otherGroup = createGroup("Strangers");
        long stranger = addMember(otherGroup, "Stranger");

        MvcTestResult result = postExpense("stranger", expenseJson(anna, 1000, "Sneaky", "EQUAL",
                "[{\"memberId\": %d}, {\"memberId\": %d}]".formatted(anna, stranger)));

        assertThat(result).hasStatus(422);
        assertThat(countExpenses()).isZero();
    }

    @Test
    void invalidBodyListsFieldErrors() {
        MvcTestResult result = postExpense("invalid", expenseJson(anna, -5, "", "EQUAL", "[]"));

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.errors[*].field").asArray()
                .contains("amountCents", "description", "participants");
    }

    @Test
    void expenseIsOnlyVisibleInItsOwnGroup() {
        long expenseId = idOf(postExpense("visible", equalSplit(anna, 1000, "Visible")));
        long otherGroup = createGroup("Elsewhere");

        assertThat(mvc.get().uri("/groups/{g}/expenses/{e}", groupId, expenseId)).hasStatus(200);
        assertThat(mvc.get().uri("/groups/{g}/expenses/{e}", otherGroup, expenseId)).hasStatus(404);
    }

    private MvcTestResult postExpense(String idempotencyKey, String body) {
        return mvc.post().uri("/groups/{id}/expenses", groupId)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private String equalSplit(long payer, long amountCents, String description) {
        return expenseJson(payer, amountCents, description, "EQUAL",
                "[{\"memberId\": %d}, {\"memberId\": %d}, {\"memberId\": %d}]".formatted(anna, ben, cem));
    }

    private static String expenseJson(long payer, long amountCents, String description, String mode, String participants) {
        return """
                {"payerId": %d, "amountCents": %d, "description": "%s", "expenseDate": "2026-10-20",
                 "splitMode": "%s", "participants": %s}
                """.formatted(payer, amountCents, description, mode, participants);
    }

    private long countExpenses() {
        return jdbc.queryForObject("select count(*) from expense where group_id = ?", Long.class, groupId);
    }
}
