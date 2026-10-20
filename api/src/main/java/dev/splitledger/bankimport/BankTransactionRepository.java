package dev.splitledger.bankimport;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

interface BankTransactionRepository extends JpaRepository<BankTransaction, Long> {

    List<BankTransaction> findByGroupIdAndMemberIdAndStatusOrderByBookingDateDescIdDesc(
            Long groupId, Long memberId, BankTransaction.Status status);

    /** SELECT ... FOR UPDATE: two confirmations of the same row run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from BankTransaction t where t.id = :id and t.groupId = :groupId and t.memberId = :memberId")
    Optional<BankTransaction> findOwnForUpdate(Long id, Long groupId, Long memberId);
}
