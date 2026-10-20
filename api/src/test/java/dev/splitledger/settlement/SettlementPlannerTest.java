package dev.splitledger.settlement;

import dev.splitledger.ledger.MemberBalance;
import dev.splitledger.settlement.SettlementPlanner.Transfer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementPlannerTest {

    @Test
    void largestDebtorPaysLargestCreditor() {
        List<Transfer> plan = SettlementPlanner.plan(balances(666, -333, -333));

        assertThat(plan).containsExactly(new Transfer(2, 1, 333), new Transfer(3, 1, 333));
    }

    @Test
    void nothingToDoWhenEveryoneIsEven() {
        assertThat(SettlementPlanner.plan(balances(0, 0, 0))).isEmpty();
        assertThat(SettlementPlanner.plan(List.of())).isEmpty();
    }

    @Test
    void greedyIsNotAlwaysMinimal() {
        // Members 1..5: +5, -6, -7, +6, +2. Minimal is 3 payments ({+6,-6} and {+5,+2,-7}); greedy
        // first pays 6 from the -7 to the +6 and needs 4, which is still within n - 1.
        List<MemberBalance> balances = balances(5, -6, -7, 6, 2);

        List<Transfer> plan = SettlementPlanner.plan(balances);

        assertThat(plan).hasSize(4);
        assertThat(minimalNumberOfPayments(balances)).isEqualTo(3);
        assertSettlesEverything(balances, plan);
    }

    @Test
    void rejectsInconsistentBalances() {
        assertThatThrownBy(() -> SettlementPlanner.plan(balances(100, -99)))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Over many random groups: always settles, never more than n-1 payments, never below the optimum. */
    @Test
    void randomGroupsAreAlwaysSettledWithinNMinusOnePayments() {
        Random random = new Random(1);
        int minimal = 0;
        int runs = 5_000;
        for (int run = 0; run < runs; run++) {
            int n = 2 + random.nextInt(6);
            long[] cents = new long[n];
            long sum = 0;
            for (int i = 0; i < n - 1; i++) {
                cents[i] = random.nextInt(2_001) - 1_000;
                sum += cents[i];
            }
            cents[n - 1] = -sum;
            List<MemberBalance> balances = balances(cents);

            List<Transfer> plan = SettlementPlanner.plan(balances);

            long nonZero = balances.stream().filter(b -> b.balanceCents() != 0).count();
            assertSettlesEverything(balances, plan);
            assertThat((long) plan.size()).isLessThanOrEqualTo(Math.max(0, nonZero - 1));
            assertThat(plan).allSatisfy(t -> assertThat(t.amountCents()).isPositive());
            int optimum = minimalNumberOfPayments(balances);
            assertThat(plan.size()).isGreaterThanOrEqualTo(optimum);
            if (plan.size() == optimum) {
                minimal++;
            }
        }
        System.out.printf("Greedy settle-up was minimal in %d of %d random groups%n", minimal, runs);
    }

    private static void assertSettlesEverything(List<MemberBalance> balances, List<Transfer> plan) {
        Map<Long, Long> after = new HashMap<>();
        balances.forEach(b -> after.put(b.memberId(), b.balanceCents()));
        for (Transfer t : plan) {
            after.merge(t.fromMemberId(), t.amountCents(), Long::sum);
            after.merge(t.toMemberId(), -t.amountCents(), Long::sum);
        }
        assertThat(after.values()).allMatch(v -> v == 0);
    }

    /**
     * Brute force for small groups: the minimum number of payments is (non-zero members) minus the
     * largest number of groups with sum zero they can be split into. dp over all subsets.
     */
    static int minimalNumberOfPayments(List<MemberBalance> balances) {
        long[] v = balances.stream().mapToLong(MemberBalance::balanceCents).filter(c -> c != 0).toArray();
        int n = v.length;
        long[] sum = new long[1 << n];
        int[] groups = new int[1 << n];
        for (int mask = 1; mask < (1 << n); mask++) {
            int lowest = Integer.numberOfTrailingZeros(mask);
            sum[mask] = sum[mask & (mask - 1)] + v[lowest];
            int best = 0;
            for (int i = 0; i < n; i++) {
                if ((mask >> i & 1) == 1) {
                    best = Math.max(best, groups[mask ^ (1 << i)]);
                }
            }
            groups[mask] = best + (sum[mask] == 0 ? 1 : 0);
        }
        return n - groups[(1 << n) - 1];
    }

    private static List<MemberBalance> balances(long... cents) {
        List<MemberBalance> list = new ArrayList<>();
        for (int i = 0; i < cents.length; i++) {
            list.add(new MemberBalance(i + 1, "M" + (i + 1), cents[i]));
        }
        return list;
    }
}
