package dev.splitledger.ledger;

import dev.splitledger.common.Sha256;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The one place that defines what a ledger entry's hash covers. Writing and verifying both use it,
 * so the two can never disagree about the format.
 *
 * <p>hash = SHA-256(previousHash | groupId | seq | memberId | amountCents | kind | referenceId | createdAt)
 * with createdAt as microseconds since the epoch (exactly what PostgreSQL stores). All fields are
 * numbers or enum names, none can contain "|", so the joined string is unambiguous. The database id
 * is left out on purpose: it is only known after the insert, and seq already fixes the position.
 */
final class LedgerHasher {

    static final String GENESIS_HASH = "0".repeat(64);

    private LedgerHasher() {
    }

    static String hash(String previousHash, long groupId, long seq, long memberId, long amountCents,
                       EntryKind kind, long referenceId, Instant createdAt) {
        String content = String.join("|",
                previousHash,
                Long.toString(groupId),
                Long.toString(seq),
                Long.toString(memberId),
                Long.toString(amountCents),
                kind.name(),
                Long.toString(referenceId),
                Long.toString(ChronoUnit.MICROS.between(Instant.EPOCH, createdAt)));
        return Sha256.hex(content);
    }

    static String recompute(LedgerEntry entry) {
        return hash(entry.getPreviousHash(), entry.getGroupId(), entry.getSeq(), entry.getMemberId(),
                entry.getAmountCents(), entry.getKind(), entry.getReferenceId(), entry.getCreatedAt());
    }
}
