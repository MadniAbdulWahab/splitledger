package dev.splitledger.bankimport;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Record of one upload and its counts. The file content itself is never stored. */
@Entity
@Table(name = "bank_import")
class BankImport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StatementFormat format;

    @Column(name = "rows_read", nullable = false)
    private int rowsRead;

    @Column(name = "new_count", nullable = false)
    private int newCount;

    @Column(name = "duplicate_count", nullable = false)
    private int duplicateCount;

    @Column(name = "skipped_count", nullable = false)
    private int skippedCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BankImport() {
    }

    BankImport(long groupId, long memberId, StatementFormat format) {
        this.groupId = groupId;
        this.memberId = memberId;
        this.format = format;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    void recordCounts(int rowsRead, int newCount, int duplicateCount, int skippedCount) {
        this.rowsRead = rowsRead;
        this.newCount = newCount;
        this.duplicateCount = duplicateCount;
        this.skippedCount = skippedCount;
    }

    Long getId() {
        return id;
    }

    StatementFormat getFormat() {
        return format;
    }
}
