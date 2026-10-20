package dev.splitledger.ledger;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Extends the bare {@link Repository} instead of JpaRepository on purpose: only the methods
 * declared here exist, and none of them can update or delete an entry.
 */
interface LedgerEntryRepository extends Repository<LedgerEntry, Long> {

    <S extends LedgerEntry> List<S> saveAll(Iterable<S> entries);

    /**
     * Row lock on the group, held until the transaction ends. Every writer of a group's chain takes
     * it first, so appends to one group happen one after another and never fork the chain.
     * Different groups do not block each other.
     *
     * <p>FOR NO KEY UPDATE, not FOR UPDATE: inserting an expense or entry takes FOR KEY SHARE on the
     * group row (foreign key check). FOR UPDATE conflicts with that, so two writers that had both
     * inserted something would wait for each other (deadlock, seen in ExpenseConcurrencyTest).
     * FOR NO KEY UPDATE still excludes other appenders but is compatible with foreign key checks.
     */
    @Query(value = "select id from expense_group where id = :groupId for no key update", nativeQuery = true)
    Optional<Long> lockGroupForAppend(long groupId);

    Optional<LedgerEntry> findFirstByGroupIdOrderBySeqDesc(Long groupId);

    List<LedgerEntry> findByGroupIdOrderBySeq(Long groupId);

    List<LedgerEntry> findByKindAndReferenceIdOrderBySeq(EntryKind kind, Long referenceId);

    boolean existsByKindAndReferenceIdIn(EntryKind kind, Collection<Long> referenceIds);

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
