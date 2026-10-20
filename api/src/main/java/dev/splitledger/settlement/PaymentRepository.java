package dev.splitledger.settlement;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/** Insert and read only, like the ledger: payments are never changed. */
interface PaymentRepository extends Repository<Payment, Long> {

    Payment save(Payment payment);

    Optional<Payment> findByGroupIdAndIdempotencyKey(Long groupId, String idempotencyKey);

    List<Payment> findByGroupIdOrderByIdDesc(Long groupId);
}
