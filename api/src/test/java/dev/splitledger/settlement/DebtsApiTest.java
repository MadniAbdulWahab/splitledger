package dev.splitledger.settlement;

import com.jayway.jsonpath.JsonPath;
import dev.splitledger.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** Pairwise debts: "who owes whom", as opposed to the group's simplified transfers. */
class DebtsApiTest extends IntegrationTest {

    private long groupId;
    private long madni;
    private long tim;
    private long lucy;

    @BeforeEach
    void setUpGroup() {
        groupId = createGroup("Flat");
        madni = myMemberId(groupId);
        tim = addMember(groupId, "Tim");
        lucy = addMember(groupId, "Lucy");
        mvc.put().uri("/groups/{g}/members/me/bank-details", groupId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"iban\": \"DE81000010000001234567\"}").exchange();
    }

    @Test
    void showsWhoOwesWhomPairByPair() {
        createExpense(groupId, lucy, 2000, "Dinner", madni, lucy); // Madni owes Lucy 10,00
        createExpense(groupId, madni, 1000, "Cinema", madni, tim); // Tim owes Madni 5,00

        assertThat(debts()).containsExactly(
                List.of(madni, lucy, 1000L),
                List.of(tim, madni, 500L));

        // Same money, different question: the fewest transfers send both to Lucy.
        List<Map<String, Object>> simplified = JsonPath.read(
                body(mvc.get().uri("/groups/{g}/settlement", groupId).exchange()), "$.payments");
        assertThat(simplified).extracting(p -> ((Number) p.get("toMemberId")).longValue()).containsOnly(lucy);
    }

    @Test
    void oppositeDebtsCancelOutAndPaymentsReduceThem() {
        createExpense(groupId, tim, 600, "Tim paid", madni, tim);     // Madni owes Tim 3,00
        createExpense(groupId, madni, 400, "Madni paid", madni, tim); // Tim owes Madni 2,00
        assertThat(debts()).containsExactly(List.of(madni, tim, 100L));

        pay(madni, tim, 100);
        assertThat(debts()).isEmpty();
    }

    @Test
    void undoingAPaymentBringsTheDebtBackAndKeepsItInTheList() {
        createExpense(groupId, lucy, 2000, "Dinner", madni, lucy); // Madni owes Lucy 10,00
        long payment = longAt(payResult(madni, lucy, 1000), "$.id");
        assertThat(debts()).isEmpty();

        MvcTestResult undone = mvc.post().uri("/groups/{g}/payments/{p}/reversal", groupId, payment).exchange();

        assertThat(undone).hasStatusOk();
        assertThat(undone).bodyJson().extractingPath("$.reversed").isEqualTo(true);
        assertThat(undone).bodyJson().extractingPath("$.undoneBy").asNumber().isEqualTo((int) madni);
        assertThat(debts()).containsExactly(List.of(madni, lucy, 1000L));
        assertThat(mvc.get().uri("/groups/{g}/balances", groupId))
                .bodyJson().extractingPath("$.balances[?(@.memberId == %d)].balanceCents".formatted(lucy))
                .asArray().containsExactly(1000);
        assertThat(mvc.get().uri("/groups/{g}/payments", groupId)).bodyJson()
                .extractingPath("$[0].reversed").isEqualTo(true);

        // Once is enough; another group cannot touch it.
        assertThat(mvc.post().uri("/groups/{g}/payments/{p}/reversal", groupId, payment)).hasStatus(409);
        long otherGroup = createGroup("Other");
        assertThat(mvc.post().uri("/groups/{g}/payments/{p}/reversal", otherGroup, payment)).hasStatus(404);
    }

    @Test
    void reversedExpensesDoNotCount() {
        long expense = createExpense(groupId, lucy, 3000, "Wrong", madni, tim, lucy);
        mvc.post().uri("/groups/{g}/expenses/{e}/reversal", groupId, expense).exchange();

        assertThat(debts()).isEmpty();
    }

    /** For every member, what others owe them minus what they owe equals their ledger balance. */
    @Test
    void pairwiseDebtsAlwaysAddUpToTheBalances() {
        long[] people = {madni, tim, lucy, addMember(groupId, "Ali")};
        Random random = new Random(3);
        for (int i = 0; i < 25; i++) {
            long payer = people[random.nextInt(people.length)];
            long other = people[random.nextInt(people.length)];
            long third = people[random.nextInt(people.length)];
            if (other == payer) other = payer == people[0] ? people[1] : people[0];
            createExpense(groupId, payer, 1 + random.nextInt(9_999), "Random " + i,
                    third == payer || third == other ? new long[]{payer, other} : new long[]{payer, other, third});
            if (i % 7 == 6) {
                pay(people[1], people[0], 1 + random.nextInt(500));
            }
        }

        Map<Long, Long> fromDebts = new HashMap<>();
        for (List<Long> debt : debts()) {
            fromDebts.merge(debt.get(0), -debt.get(2), Long::sum);
            fromDebts.merge(debt.get(1), debt.get(2), Long::sum);
        }
        List<Map<String, Object>> balances = JsonPath.read(
                body(mvc.get().uri("/groups/{g}/balances", groupId).exchange()), "$.balances");
        for (Map<String, Object> balance : balances) {
            long member = ((Number) balance.get("memberId")).longValue();
            assertThat(fromDebts.getOrDefault(member, 0L)).as("member %d", member)
                    .isEqualTo(((Number) balance.get("balanceCents")).longValue());
        }
    }

    @Test
    void giroCodeForAPairDebtWithItsAmount() throws Exception {
        createExpense(groupId, madni, 1000, "Cinema", madni, tim);

        MvcTestResult png = mvc.get().uri("/groups/{g}/settlement/girocode?from={f}&to={t}&amount=500", groupId, tim, madni)
                .exchange();

        assertThat(png).hasStatusOk();
        String[] lines = GiroCodeTest.decode(png.getResponse().getContentAsByteArray()).split("\n", -1);
        assertThat(lines[6]).isEqualTo("DE81000010000001234567");
        assertThat(lines[7]).isEqualTo("EUR5.00");

        assertThat(mvc.get().uri("/groups/{g}/settlement/girocode?from={f}&to={t}&amount=500", groupId, madni, madni))
                .hasStatus(422);
        assertThat(mvc.get().uri("/groups/{g}/settlement/girocode?from={f}&to={t}&amount=0", groupId, tim, madni))
                .hasStatus(400);
    }

    private List<List<Long>> debts() {
        List<Map<String, Object>> debts = JsonPath.read(body(mvc.get().uri("/groups/{g}/debts", groupId).exchange()), "$.debts");
        return debts.stream()
                .map(d -> List.of(((Number) d.get("fromMemberId")).longValue(), ((Number) d.get("toMemberId")).longValue(),
                        ((Number) d.get("amountCents")).longValue()))
                .toList();
    }

    private void pay(long from, long to, long cents) {
        assertThat(payResult(from, to, cents)).hasStatus(201);
    }

    private MvcTestResult payResult(long from, long to, long cents) {
        return mvc.post().uri("/groups/{g}/payments", groupId)
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromMemberId\": %d, \"toMemberId\": %d, \"amountCents\": %d}".formatted(from, to, cents))
                .exchange();
    }
}
