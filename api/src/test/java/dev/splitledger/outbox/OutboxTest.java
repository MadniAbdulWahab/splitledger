package dev.splitledger.outbox;

import com.jayway.jsonpath.JsonPath;
import dev.splitledger.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Events are written in the same transaction as the business change; no SQS involved here. */
class OutboxTest extends IntegrationTest {

    @Autowired
    private JsonMapper json;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long groupId;
    private long anna;
    private long ben;

    @BeforeEach
    void setUpGroup() {
        groupId = createGroup("Outbox");
        anna = myMemberId(groupId);
        ben = addMember(groupId, "Ben");
    }

    @Test
    void everyBusinessEventWritesOneOutboxRowWithItsLedgerPostings() {
        long expenseId = createExpense(groupId, anna, 1000, "Groceries", anna, ben);
        mvc.post().uri("/groups/{g}/expenses/{e}/reversal", groupId, expenseId).exchange();
        mvc.post().uri("/groups/{g}/payments", groupId)
                .header("Idempotency-Key", "outbox-payment")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromMemberId\": %d, \"toMemberId\": %d, \"amountCents\": 250}".formatted(ben, anna))
                .exchange();

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select event_type, payload::text as payload from outbox_event where group_id = ? order by id", groupId);

        assertThat(rows).extracting(row -> row.get("event_type"))
                .containsExactly("EXPENSE_RECORDED", "EXPENSE_REVERSED", "PAYMENT_RECORDED");
        String recorded = (String) rows.get(0).get("payload");
        assertThat((Integer) JsonPath.read(recorded, "$.referenceId")).isEqualTo((int) expenseId);
        assertThat((String) JsonPath.read(recorded, "$.occurredOn")).isEqualTo("2026-10-21");
        assertThat((String) JsonPath.read(recorded, "$.description")).isEqualTo("Groceries");
        assertThat(JsonPath.<List<Integer>>read(recorded, "$.postings[*].amountCents")).containsExactly(1000, -500, -500);
        assertThat(JsonPath.<List<String>>read(recorded, "$.postings[*].displayName")).containsExactly("Anna", "Anna", "Ben");
        // The reversal carries the opposite postings, so a consumer can subtract the expense again.
        assertThat(JsonPath.<List<Integer>>read((String) rows.get(1).get("payload"), "$.postings[*].amountCents"))
                .containsExactly(-1000, 500, 500);
        assertThat(JsonPath.<List<Integer>>read((String) rows.get(2).get("payload"), "$.postings[*].amountCents"))
                .containsExactly(250, -250);
    }

    @Test
    void rejectedRequestsWriteNoEvent() {
        mvc.post().uri("/groups/{g}/expenses", groupId)
                .header("Idempotency-Key", "too-much")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"payerId": %d, "amountCents": 1000, "description": "Bad split", "expenseDate": "2026-10-21",
                         "splitMode": "EXACT", "participants": [{"memberId": %d, "value": 999}]}
                        """.formatted(anna, anna))
                .exchange();

        assertThat(jdbc.queryForObject("select count(*) from outbox_event where group_id = ?", Long.class, groupId))
                .isZero();
    }

    /** SQS fails on the second message: only the first is marked, the rest waits for the next run. */
    @Test
    void publisherStopsAtTheFirstFailureAndMarksOnlyWhatWasSent() {
        createExpense(groupId, anna, 100, "First", anna, ben);
        createExpense(groupId, anna, 200, "Second", anna, ben);
        createExpense(groupId, anna, 300, "Third", anna, ben);
        jdbc.update("update outbox_event set published_at = now() where group_id <> ? and published_at is null", groupId);

        SqsClient sqs = mock(SqsClient.class);
        AtomicInteger calls = new AtomicInteger();
        when(sqs.sendMessage(any(Consumer.class))).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                throw SqsException.builder().message("Simulated outage").build();
            }
            return SendMessageResponse.builder().messageId("m" + calls.get()).build();
        });
        OutboxPublisher publisher = new OutboxPublisher(jdbc, sqs, json, transactionManager, "https://example/queue.fifo");

        assertThat(publisher.publishBatch()).isEqualTo(1);
        List<String> published = jdbc.queryForList("""
                select payload->>'description' from outbox_event
                where group_id = ? and published_at is not null order by id""", String.class, groupId);
        assertThat(published).containsExactly("First");

        assertThat(publisher.publishPending()).as("next run, SQS healthy again").isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where group_id = ? and published_at is null",
                Long.class, groupId)).isZero();
    }
}
