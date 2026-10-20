package dev.splitledger.common;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Creates something exactly once per idempotency key (expenses, payments).
 *
 * <ol>
 *   <li>Look for an earlier creation with the key; if found, replay it (or fail with 409 if the
 *       body differs; {@code findEarlier} decides that).</li>
 *   <li>Otherwise insert. If two identical requests race, both pass step 1, and the database's
 *       unique constraint on the key lets exactly one insert win.</li>
 *   <li>The loser's transaction is rolled back; it then reads the winner in a new transaction and
 *       answers as if it were a retry.</li>
 * </ol>
 * That is why this uses TransactionTemplate instead of one @Transactional method: step 3 needs a
 * fresh transaction after the failed one.
 */
@Component
public class IdempotentCreator {

    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    IdempotentCreator(PlatformTransactionManager transactionManager) {
        this.writeTx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    public record Result<T>(T value, boolean replayed) {
    }

    public <T> Result<T> create(String keyConstraintName, Supplier<Optional<T>> findEarlier, Supplier<T> insert) {
        Optional<T> earlier = readTx.execute(status -> findEarlier.get());
        if (earlier.isPresent()) {
            return new Result<>(earlier.get(), true);
        }
        try {
            return new Result<>(writeTx.execute(status -> insert.get()), false);
        } catch (DataIntegrityViolationException e) {
            if (!violates(e, keyConstraintName)) {
                throw e;
            }
            return readTx.execute(status -> findEarlier.get())
                    .map(value -> new Result<>(value, true))
                    .orElseThrow(() -> e);
        }
    }

    private static boolean violates(DataIntegrityViolationException e, String constraintName) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return constraintName.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }
}
