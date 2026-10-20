package dev.splitledger.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * One line of the double-entry ledger. Positive amount: the group owes the member; negative: the
 * member owes the group. {@code @Immutable} makes Hibernate ignore changes to loaded entries, so
 * a stray setter could never turn into an UPDATE statement.
 */
@Entity
@Immutable
@Table(name = "ledger_entry")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(nullable = false)
    private long seq;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EntryKind kind;

    @Column(name = "reference_id", nullable = false)
    private Long referenceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "previous_hash", nullable = false, length = 64)
    private String previousHash;

    @Column(nullable = false, length = 64)
    private String hash;

    protected LedgerEntry() {
    }

    LedgerEntry(long groupId, long seq, long memberId, long amountCents, EntryKind kind, long referenceId,
                String previousHash) {
        this.groupId = groupId;
        this.seq = seq;
        this.memberId = memberId;
        this.amountCents = amountCents;
        this.kind = kind;
        this.referenceId = referenceId;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.previousHash = previousHash;
        this.hash = LedgerHasher.hash(previousHash, groupId, seq, memberId, amountCents, kind, referenceId, createdAt);
    }

    public Long getId() {
        return id;
    }

    public Long getGroupId() {
        return groupId;
    }

    public long getSeq() {
        return seq;
    }

    public Long getMemberId() {
        return memberId;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public EntryKind getKind() {
        return kind;
    }

    public Long getReferenceId() {
        return referenceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getPreviousHash() {
        return previousHash;
    }

    public String getHash() {
        return hash;
    }
}
