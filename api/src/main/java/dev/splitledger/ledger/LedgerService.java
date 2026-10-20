package dev.splitledger.ledger;

import dev.splitledger.common.ConflictException;
import dev.splitledger.common.NotFoundException;
import dev.splitledger.group.GroupService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class LedgerService {

    private final LedgerEntryRepository entries;
    private final GroupService groupService;

    LedgerService(LedgerEntryRepository entries, GroupService groupService) {
        this.entries = entries;
        this.groupService = groupService;
    }

    /** An entry to be appended; the chain fields (seq, hashes) are added in {@link #append}. */
    private record Draft(long memberId, long amountCents, EntryKind kind, long referenceId) {
    }

    /**
     * Writes the entries of one business event. Must run inside the caller's transaction, so the
     * expense and its entries are committed together or not at all.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<LedgerEntry> post(long groupId, EntryKind kind, long referenceId, List<Posting> postings) {
        long sum = postings.stream().mapToLong(Posting::amountCents).sum();
        if (sum != 0) {
            // A programming error, not a user error: callers must always build balanced postings.
            throw new IllegalStateException("Postings for " + kind + " " + referenceId + " sum to " + sum + ", not 0");
        }
        lockChain(groupId);
        return append(groupId, postings.stream()
                .filter(posting -> posting.amountCents() != 0)
                .map(posting -> new Draft(posting.memberId(), posting.amountCents(), kind, referenceId))
                .toList());
    }

    /**
     * Cancels a whole business event: every original entry gets a REVERSAL entry with the opposite
     * amount that references it. The originals stay untouched, so the history shows both.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<LedgerEntry> reverse(long groupId, EntryKind kind, long referenceId) {
        lockChain(groupId);
        List<LedgerEntry> originals = entries.findByKindAndReferenceIdOrderBySeq(kind, referenceId).stream()
                .filter(entry -> entry.getGroupId() == groupId)
                .toList();
        if (originals.isEmpty()) {
            throw new NotFoundException("No ledger entries for " + kind + " " + referenceId + " in group " + groupId);
        }
        // Checked under the group lock, so two concurrent reversals cannot both pass. The partial
        // unique index ledger_entry_reversed_once is the database's backstop for the same rule.
        if (entries.existsByKindAndReferenceIdIn(EntryKind.REVERSAL, originals.stream().map(LedgerEntry::getId).toList())) {
            throw new ConflictException(kind + " " + referenceId + " has already been reversed");
        }
        return append(groupId, originals.stream()
                .map(original -> new Draft(original.getMemberId(), -original.getAmountCents(),
                        EntryKind.REVERSAL, original.getId()))
                .toList());
    }

    @Transactional(readOnly = true)
    public boolean isReversed(EntryKind kind, long referenceId) {
        List<Long> ids = entries.findByKindAndReferenceIdOrderBySeq(kind, referenceId).stream()
                .map(LedgerEntry::getId)
                .toList();
        return !ids.isEmpty() && entries.existsByKindAndReferenceIdIn(EntryKind.REVERSAL, ids);
    }

    @Transactional(readOnly = true)
    public List<MemberBalance> balances(long groupId) {
        groupService.requireGroup(groupId);
        return entries.balancesOf(groupId);
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> entriesFor(EntryKind kind, long referenceId) {
        return entries.findByKindAndReferenceIdOrderBySeq(kind, referenceId);
    }

    /**
     * Recomputes the group's chain from the first entry and reports the first entry that does not
     * fit: a gap in seq (an entry was deleted), a previous_hash that does not match the entry before
     * (entries reordered or removed), or a hash that does not match the content (entry changed).
     */
    @Transactional(readOnly = true)
    public ChainVerification verify(long groupId) {
        groupService.requireGroup(groupId);
        List<LedgerEntry> chain = entries.findByGroupIdOrderBySeq(groupId);

        long expectedSeq = 1;
        String expectedPrevious = LedgerHasher.GENESIS_HASH;
        long sum = 0;
        for (LedgerEntry entry : chain) {
            String problem = null;
            if (entry.getSeq() != expectedSeq) {
                problem = "expected seq " + expectedSeq + " but found " + entry.getSeq() + "; entries are missing";
            } else if (!entry.getPreviousHash().equals(expectedPrevious)) {
                problem = "previous_hash does not match the hash of the entry before";
            } else if (!LedgerHasher.recompute(entry).equals(entry.getHash())) {
                problem = "content does not match the stored hash";
            }
            if (problem != null) {
                return ChainVerification.broken(groupId, expectedSeq - 1,
                        new ChainVerification.BrokenEntry(entry.getId(), entry.getSeq(), problem));
            }
            sum += entry.getAmountCents();
            expectedPrevious = entry.getHash();
            expectedSeq++;
        }
        return ChainVerification.intact(groupId, chain.size(), expectedPrevious, sum == 0);
    }

    private void lockChain(long groupId) {
        if (entries.lockGroupForAppend(groupId).isEmpty()) {
            throw new NotFoundException("Group " + groupId + " does not exist");
        }
    }

    /** Appends entries after the current head of the group's chain. The caller holds the group lock. */
    private List<LedgerEntry> append(long groupId, List<Draft> drafts) {
        Optional<LedgerEntry> head = entries.findFirstByGroupIdOrderBySeqDesc(groupId);
        long seq = head.map(LedgerEntry::getSeq).orElse(0L);
        String previousHash = head.map(LedgerEntry::getHash).orElse(LedgerHasher.GENESIS_HASH);

        List<LedgerEntry> toWrite = new ArrayList<>(drafts.size());
        for (Draft draft : drafts) {
            seq++;
            LedgerEntry entry = new LedgerEntry(groupId, seq, draft.memberId(), draft.amountCents(), draft.kind(),
                    draft.referenceId(), previousHash);
            toWrite.add(entry);
            previousHash = entry.getHash();
        }
        return entries.saveAll(toWrite);
    }
}
