package dev.splitledger.settlement;

import com.jayway.jsonpath.JsonPath;
import dev.splitledger.IntegrationTest;
import dev.splitledger.ledger.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementApiTest extends IntegrationTest {

    @Autowired
    private LedgerService ledgerService;

    private long groupId;
    private long anna;
    private long ben;
    private long cem;

    @BeforeEach
    void setUpGroup() {
        groupId = createGroup("Trip");
        anna = myMemberId(groupId);
        ben = addMember(groupId, "Ben");
        cem = addMember(groupId, "Cem");
        mvc.put().uri("/groups/{g}/members/me/bank-details", groupId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"iban\": \"DE81000010000001234567\", \"bic\": \"TESTDEB1XXX\"}").exchange();
    }

    @Test
    void recordingTheSuggestedPaymentsSettlesTheGroup() {
        createExpense(groupId, anna, 9000, "Hotel", anna, ben, cem);
        createExpense(groupId, ben, 1500, "Fuel", ben, cem);

        MvcTestResult suggestion = mvc.get().uri("/groups/{g}/settlement", groupId).exchange();
        // Anna +6000, Ben -3000 + 1500 - 750 = -2250, Cem -3000 - 750 = -3750
        assertThat(suggestion).bodyJson().extractingPath("$.payments[0]").isEqualTo(Map.of(
                "fromMemberId", (int) cem, "fromName", "Cem", "toMemberId", (int) anna, "toName", "Anna",
                "toIban", "DE81000010000001234567", "toBic", "TESTDEB1XXX", "amountCents", 3750));
        assertThat(suggestion).bodyJson().extractingPath("$.payments[1].amountCents").isEqualTo(2250);

        List<Map<String, Object>> payments = JsonPath.read(body(suggestion), "$.payments");
        for (Map<String, Object> p : payments) {
            assertThat(pay("settle-" + p.get("fromMemberId"), ((Number) p.get("fromMemberId")).longValue(),
                    ((Number) p.get("toMemberId")).longValue(), ((Number) p.get("amountCents")).longValue()))
                    .hasStatus(201);
        }

        assertThat(mvc.get().uri("/groups/{g}/balances", groupId))
                .bodyJson().extractingPath("$.balances[*].balanceCents").asArray().containsOnly(0);
        assertThat(mvc.get().uri("/groups/{g}/settlement", groupId))
                .bodyJson().extractingPath("$.payments").asArray().isEmpty();
        assertThat(mvc.get().uri("/groups/{g}/payments", groupId))
                .bodyJson().extractingPath("$").asArray().hasSize(2);
        assertThat(ledgerService.verify(groupId).valid()).isTrue();
    }

    @Test
    void giroCodeEncodesTheSuggestedPaymentForABankingApp() throws Exception {
        createExpense(groupId, anna, 4200, "Groceries", ben);

        MvcTestResult png = mvc.get().uri("/groups/{g}/settlement/girocode?from={f}&to={t}", groupId, ben, anna).exchange();

        assertThat(png).hasStatusOk().hasContentType(MediaType.IMAGE_PNG);
        assertThat(png.getResponse().getHeader("Cache-Control")).contains("no-store");
        String[] lines = GiroCodeTest.decode(png.getResponse().getContentAsByteArray()).split("\n", -1);
        assertThat(lines[0]).isEqualTo("BCD");
        assertThat(lines[4]).isEqualTo("TESTDEB1XXX");
        assertThat(lines[5]).isEqualTo("Anna");
        assertThat(lines[6]).isEqualTo("DE81000010000001234567");
        assertThat(lines[7]).isEqualTo("EUR42.00");
        assertThat(lines[10]).startsWith("SplitLedger settle-up Trip ");
    }

    @Test
    void giroCodeNeedsAnIbanAndASuggestedPayment() {
        createExpense(groupId, ben, 1000, "Ben paid", anna);

        // Ben is owed money but has no IBAN in the group.
        assertThat(mvc.get().uri("/groups/{g}/settlement/girocode?from={f}&to={t}", groupId, anna, ben))
                .hasStatus(422).bodyJson().extractingPath("$.detail").asString().contains("IBAN");
        // Nobody owes Cem anything.
        assertThat(mvc.get().uri("/groups/{g}/settlement/girocode?from={f}&to={t}", groupId, anna, cem))
                .hasStatus(404);
    }

    @Test
    void paymentsAreIdempotent() {
        MvcTestResult first = pay("pay-1", ben, anna, 1000);
        MvcTestResult retry = pay("pay-1", ben, anna, 1000);

        assertThat(retry).hasStatus(201).hasHeader("Idempotent-Replayed", "true");
        assertThat(idOf(retry)).isEqualTo(idOf(first));
        assertThat(pay("pay-1", ben, anna, 1200)).hasStatus(409);
        assertThat(jdbc.queryForObject("select count(*) from payment where group_id = ?", Long.class, groupId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ledger_entry where group_id = ? and kind = 'PAYMENT'",
                Long.class, groupId)).isEqualTo(2);
    }

    @Test
    void rejectsInvalidPayments() {
        long otherGroup = createGroup("Other");
        long stranger = addMember(otherGroup, "Stranger");

        assertThat(pay("self", anna, anna, 100)).hasStatus(422);
        assertThat(pay("outsider", stranger, anna, 100)).hasStatus(422);
        assertThat(pay("zero", ben, anna, 0)).hasStatus(400);
        assertThatPaymentIsAppendOnly();
    }

    private void assertThatPaymentIsAppendOnly() {
        pay("for-trigger", ben, anna, 100);
        assertThatThrownBy(() ->
                        jdbc.update("update payment set amount_cents = 1 where group_id = ?", groupId))
                .hasMessageContaining("payment is append-only");
    }

    private MvcTestResult pay(String key, long from, long to, long cents) {
        return mvc.post().uri("/groups/{g}/payments", groupId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromMemberId\": %d, \"toMemberId\": %d, \"amountCents\": %d}".formatted(from, to, cents))
                .exchange();
    }
}
