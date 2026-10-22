package dev.splitledger.outbox;

import com.jayway.jsonpath.JsonPath;
import dev.splitledger.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Publishes to a real SQS-compatible FIFO queue: ElasticMQ in a container. (LocalStack would also
 * do, but since 2026 it needs an account token, which a public CI should not depend on.)
 */
class OutboxPublisherSqsTest extends IntegrationTest {

    static final GenericContainer<?> ELASTICMQ = new GenericContainer<>("softwaremill/elasticmq-native:1.7.1")
            .withExposedPorts(9324)
            .waitingFor(Wait.forListeningPort());
    static String queueUrl;

    @Autowired
    private OutboxPublisher publisher;

    @DynamicPropertySource
    static void queue(DynamicPropertyRegistry registry) {
        ELASTICMQ.start();
        String endpoint = "http://" + ELASTICMQ.getHost() + ":" + ELASTICMQ.getMappedPort(9324);
        try (SqsClient sqs = client(endpoint)) {
            queueUrl = sqs.createQueue(q -> q.queueName("ledger-events-test.fifo").attributes(Map.of(
                    QueueAttributeName.FIFO_QUEUE, "true"))).queueUrl();
        }
        registry.add("splitledger.outbox.queue-url", () -> queueUrl);
        registry.add("splitledger.outbox.endpoint", () -> endpoint);
        // The test calls the publisher itself; the schedule must not interfere.
        registry.add("splitledger.outbox.poll-interval", () -> "PT1H");
    }

    @Test
    void publishesEventsInOrderPerGroupExactlyOnce() {
        long groupId = createGroup("Queued");
        long anna = myMemberId(groupId);
        long ben = addMember(groupId, "Ben");
        createExpense(groupId, anna, 1200, "Dinner", anna, ben);
        mvc.post().uri("/groups/{g}/payments", groupId)
                .header("Idempotency-Key", "queued-payment")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromMemberId\": %d, \"toMemberId\": %d, \"amountCents\": 600}".formatted(ben, anna))
                .exchange();

        assertThat(publisher.publishPending()).isEqualTo(2);
        assertThat(publisher.publishPending()).as("second run").isZero();

        List<Message> messages = receiveAll();
        assertThat(messages).hasSize(2);
        assertThat(messages).allSatisfy(m ->
                assertThat(m.attributes().get(MessageSystemAttributeName.MESSAGE_GROUP_ID)).isEqualTo("group-" + groupId));
        List<String> types = messages.stream().map(m -> JsonPath.<String>read(m.body(), "$.type")).toList();
        assertThat(types).containsExactly("EXPENSE_RECORDED", "PAYMENT_RECORDED");
        long firstId = ((Number) JsonPath.read(messages.get(0).body(), "$.eventId")).longValue();
        long secondId = ((Number) JsonPath.read(messages.get(1).body(), "$.eventId")).longValue();
        assertThat(secondId).isGreaterThan(firstId);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where group_id = ? and published_at is null",
                Long.class, groupId)).isZero();
    }

    private static List<Message> receiveAll() {
        String endpoint = "http://" + ELASTICMQ.getHost() + ":" + ELASTICMQ.getMappedPort(9324);
        List<Message> all = new ArrayList<>();
        try (SqsClient sqs = client(endpoint)) {
            List<Message> batch;
            do {
                batch = sqs.receiveMessage(r -> r.queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(1)
                        .messageSystemAttributeNames(MessageSystemAttributeName.MESSAGE_GROUP_ID)).messages();
                all.addAll(batch);
                for (Message m : batch) {
                    sqs.deleteMessage(d -> d.queueUrl(queueUrl).receiptHandle(m.receiptHandle()));
                }
            } while (!batch.isEmpty());
        }
        return all;
    }

    private static SqsClient client(String endpoint) {
        return SqsClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.EU_CENTRAL_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .build();
    }
}
