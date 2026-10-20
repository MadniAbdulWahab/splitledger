package dev.splitledger.ledger;

import dev.splitledger.group.GroupService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class LedgerService {

    private final LedgerEntryRepository entries;
    private final GroupService groupService;

    LedgerService(LedgerEntryRepository entries, GroupService groupService) {
        this.entries = entries;
        this.groupService = groupService;
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
        List<LedgerEntry> toWrite = postings.stream()
                .filter(posting -> posting.amountCents() != 0)
                .map(posting -> new LedgerEntry(groupId, posting.memberId(), posting.amountCents(), kind, referenceId))
                .toList();
        return entries.saveAll(toWrite);
    }

    @Transactional(readOnly = true)
    public List<MemberBalance> balances(long groupId) {
        groupService.requireGroup(groupId);
        return entries.balancesOf(groupId);
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> entriesFor(EntryKind kind, long referenceId) {
        return entries.findByKindAndReferenceIdOrderById(kind, referenceId);
    }
}
