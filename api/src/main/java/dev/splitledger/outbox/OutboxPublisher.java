package dev.splitledger.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Sends committed outbox rows to the SQS FIFO queue, oldest first, and marks them as published.
 *
 * <p>Delivery is at-least-once: if the process dies after SQS accepted a message but before the
 * row was marked, the row is sent again. Two things absorb that: SQS FIFO drops a repeated
 * deduplication id within five minutes, and the worker skips events it has already applied.
 *
 * <p>Order matters to the worker, so messages are sent one by one and the batch stops at the first
 * failure; nothing can overtake an event that is still waiting. A PostgreSQL advisory lock makes
 * sure only one publisher runs at a time, even with several API tasks.
 */
@Component
@ConditionalOnProperty("splitledger.outbox.queue-url")
class OutboxPublisher {

    static final int BATCH_SIZE = 10;
    /** Any constant shared by all publishers; it names the lock, it has no other meaning. */
    private static final long PUBLISHER_LOCK = 20261022L;
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcTemplate jdbc;
    private final SqsClient sqs;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final String queueUrl;

    OutboxPublisher(JdbcTemplate jdbc, SqsClient sqs, JsonMapper json, PlatformTransactionManager transactionManager,
                    @Value("${splitledger.outbox.queue-url}") String queueUrl) {
        this.jdbc = jdbc;
        this.sqs = sqs;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
        this.queueUrl = queueUrl;
    }

    private record PendingEvent(long id, long groupId, String payload) {
    }

    @Scheduled(fixedDelayString = "${splitledger.outbox.poll-interval:PT5S}")
    void publishPeriodically() {
        try {
            publishPending();
        } catch (RuntimeException e) {
            // Nothing is lost: unsent rows stay unpublished and the next run tries again.
            log.warn("Publishing outbox events failed; retrying in the next run", e);
        }
    }

    /** Publishes batches until nothing is left. Returns the number of events sent. */
    int publishPending() {
        int total = 0;
        int sent;
        do {
            sent = publishBatch();
            total += sent;
        } while (sent == BATCH_SIZE);
        return total;
    }

    int publishBatch() {
        Integer sent = tx.execute(status -> {
            Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, PUBLISHER_LOCK);
            if (!Boolean.TRUE.equals(locked)) {
                return 0;
            }
            List<PendingEvent> pending = jdbc.query("""
                    select id, group_id, payload::text from outbox_event
                    where published_at is null order by id limit ?""",
                    (rs, row) -> new PendingEvent(rs.getLong(1), rs.getLong(2), rs.getString(3)), BATCH_SIZE);

            List<Long> published = new ArrayList<>();
            try {
                for (PendingEvent event : pending) {
                    send(event);
                    published.add(event.id());
                }
            } catch (RuntimeException e) {
                log.warn("SQS rejected outbox event; {} of {} sent, the rest waits", published.size(), pending.size(), e);
            }
            for (Long id : published) {
                jdbc.update("update outbox_event set published_at = now() where id = ?", id);
            }
            return published.size();
        });
        return sent == null ? 0 : sent;
    }

    /**
     * Message group = expense group: SQS keeps each group's events in order and hands them to one
     * consumer at a time. The outbox id doubles as deduplication id and as the event id the worker
     * uses to skip repeats.
     */
    private void send(PendingEvent event) {
        ObjectNode body = (ObjectNode) json.readTree(event.payload());
        body.put("eventId", event.id());
        sqs.sendMessage(message -> message
                .queueUrl(queueUrl)
                .messageGroupId("group-" + event.groupId())
                .messageDeduplicationId("outbox-" + event.id())
                .messageBody(json.writeValueAsString(body)));
    }
}
