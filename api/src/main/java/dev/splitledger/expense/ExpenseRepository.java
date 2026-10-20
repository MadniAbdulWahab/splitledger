package dev.splitledger.expense;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface ExpenseRepository extends JpaRepository<Expense, Long> {

    Optional<Expense> findByGroupIdAndIdempotencyKey(Long groupId, String idempotencyKey);

    Optional<Expense> findByIdAndGroupId(Long id, Long groupId);
}
