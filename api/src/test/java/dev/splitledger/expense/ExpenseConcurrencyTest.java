package dev.splitledger.expense;

import dev.splitledger.IntegrationTest;
import dev.splitledger.common.ConflictException;
import dev.splitledger.ledger.ChainVerification;
import dev.splitledger.ledger.LedgerService;
import dev.splitledger.expense.ExpenseDtos.CreateExpenseRequest;
import dev.splitledger.expense.ExpenseDtos.ParticipantRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fires requests from many threads at the same moment (released by one latch) to hit the races
 * that a single-threaded test never sees.
 */
class ExpenseConcurrencyTest extends IntegrationTest {

    private static final int THREADS = 16;

    @Autowired
    private ExpenseService expenseService;

    @Autowired
    private LedgerService ledgerService;

    @Test
    void sameIdempotencyKeyFromManyThreadsCreatesExactlyOneExpense() throws Exception {
        long groupId = createGroup("Race same key");
        long anna = addMember(groupId, "Anna");
        long ben = addMember(groupId, "Ben");
        CreateExpenseRequest request = request(anna, 1234, List.of(anna, ben));

        List<ExpenseService.Creation> results = runConcurrently(i -> expenseService.create(groupId, "same-key", request));

        assertThat(results).extracting(c -> c.expense().id()).containsOnly(results.getFirst().expense().id());
        assertThat(results).filteredOn(c -> !c.replayed()).hasSize(1);
        assertThat(count("select count(*) from expense where group_id = ?", groupId)).isEqualTo(1);
        assertThat(count("select count(*) from ledger_entry where group_id = ?", groupId)).isEqualTo(3);
    }

    @Test
    void parallelExpensesKeepEveryEventAndTheGroupBalanced() throws Exception {
        long groupId = createGroup("Race different keys");
        long anna = addMember(groupId, "Anna");
        long ben = addMember(groupId, "Ben");
        long cem = addMember(groupId, "Cem");
        List<Long> members = List.of(anna, ben, cem);

        runConcurrently(i -> expenseService.create(groupId, "key-" + i,
                request(members.get(i % 3), 1000 + i, members)));

        assertThat(count("select count(*) from expense where group_id = ?", groupId)).isEqualTo(THREADS);
        assertThat(count("select coalesce(sum(amount_cents), 0) from ledger_entry where group_id = ?", groupId)).isZero();
        assertThat(count("""
                select count(*) from (
                    select reference_id from ledger_entry where group_id = ?
                    group by reference_id having sum(amount_cents) <> 0) unbalanced
                """, groupId)).isZero();

        // 16 expenses x 4 entries, appended by 16 threads, still form one gap-free, unforked chain.
        ChainVerification chain = ledgerService.verify(groupId);
        assertThat(chain.valid()).isTrue();
        assertThat(chain.entriesChecked()).isEqualTo(THREADS * 4L);
    }

    @Test
    void concurrentReversalsOfOneExpenseSucceedExactlyOnce() throws Exception {
        long groupId = createGroup("Race reversal");
        long anna = addMember(groupId, "Anna");
        long ben = addMember(groupId, "Ben");
        long expenseId = createExpense(groupId, anna, 500, "Reverse me", anna, ben);

        List<String> outcomes = runConcurrently(i -> {
            try {
                expenseService.reverse(groupId, expenseId);
                return "reversed";
            } catch (ConflictException e) {
                return "conflict";
            }
        });

        assertThat(outcomes).filteredOn("reversed"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("conflict"::equals).hasSize(THREADS - 1);
        assertThat(ledgerService.verify(groupId).valid()).isTrue();
        assertThat(ledgerService.balances(groupId)).allSatisfy(b -> assertThat(b.balanceCents()).isZero());
    }

    private interface Task<T> {
        T run(int index) throws Exception;
    }

    private static <T> List<T> runConcurrently(Task<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                int index = i;
                Callable<T> call = () -> {
                    start.await();
                    return task.run(index);
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    private static CreateExpenseRequest request(long payer, long amountCents, List<Long> participants) {
        return new CreateExpenseRequest(payer, amountCents, "Concurrent", null, LocalDate.of(2026, 10, 20),
                SplitMode.EQUAL, participants.stream().map(id -> new ParticipantRequest(id, null)).toList());
    }

    private long count(String sql, long groupId) {
        return jdbc.queryForObject(sql, Long.class, groupId);
    }
}
