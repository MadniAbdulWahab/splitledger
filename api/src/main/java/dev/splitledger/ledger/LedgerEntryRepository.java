package dev.splitledger.ledger;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * Extends the bare {@link Repository} instead of JpaRepository on purpose: only the methods
 * declared here exist, and none of them can update or delete an entry.
 */
interface LedgerEntryRepository extends Repository<LedgerEntry, Long> {

    <S extends LedgerEntry> List<S> saveAll(Iterable<S> entries);

    List<LedgerEntry> findByKindAndReferenceIdOrderById(EntryKind kind, Long referenceId);

    // Left join, so members without any entry still show up with balance 0.
    @Query("""
            select new dev.splitledger.ledger.MemberBalance(m.id, m.displayName, coalesce(sum(e.amountCents), 0L))
            from Member m left join LedgerEntry e on e.memberId = m.id
            where m.groupId = :groupId
            group by m.id, m.displayName
            order by m.id
            """)
    List<MemberBalance> balancesOf(Long groupId);
}
