package dev.splitledger.ledger;

/**
 * Result of {@code GET /groups/{id}/verify}.
 *
 * <p>{@code headHash} is the hash of the newest entry. The chain alone cannot catch someone with
 * full database access who rewrites every hash after a change; members who note the head hash
 * (e.g. share it in their group chat after settling up) can catch that too, because a rewritten
 * chain ends in a different head hash.
 */
public record ChainVerification(
        long groupId,
        boolean valid,
        long entriesChecked,
        String headHash,
        Boolean balanced,
        BrokenEntry firstBrokenEntry) {

    public record BrokenEntry(long entryId, long seq, String problem) {
    }

    static ChainVerification intact(long groupId, long entries, String headHash, boolean balanced) {
        return new ChainVerification(groupId, true, entries, headHash, balanced, null);
    }

    static ChainVerification broken(long groupId, long entriesBefore, BrokenEntry brokenEntry) {
        return new ChainVerification(groupId, false, entriesBefore, null, null, brokenEntry);
    }
}
