package dev.splitledger.settlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** A settle-up transfer one member made to another outside the app (e.g. by bank transfer). */
@Entity
@Immutable
@Table(name = "payment")
class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "from_member_id", nullable = false)
    private Long fromMemberId;

    @Column(name = "to_member_id", nullable = false)
    private Long toMemberId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(length = 200)
    private String note;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Payment() {
    }

    Payment(long groupId, long fromMemberId, long toMemberId, long amountCents, String note,
            String idempotencyKey, String requestHash) {
        this.groupId = groupId;
        this.fromMemberId = fromMemberId;
        this.toMemberId = toMemberId;
        this.amountCents = amountCents;
        this.note = note;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    Long getId() {
        return id;
    }

    Long getGroupId() {
        return groupId;
    }

    Long getFromMemberId() {
        return fromMemberId;
    }

    Long getToMemberId() {
        return toMemberId;
    }

    long getAmountCents() {
        return amountCents;
    }

    String getNote() {
        return note;
    }

    String getRequestHash() {
        return requestHash;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
