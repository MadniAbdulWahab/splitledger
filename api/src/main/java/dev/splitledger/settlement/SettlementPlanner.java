package dev.splitledger.settlement;

import dev.splitledger.ledger.MemberBalance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Suggests payments that bring every balance to zero. Greedy: the member who owes the most pays
 * the member who is owed the most, as much as possible, until nobody owes anything.
 *
 * <p>Every payment settles at least one of the two members completely, so n members with a
 * non-zero balance need at most n-1 payments. The truly minimal number of payments is NP-hard in
 * general: it means splitting the members into as many groups with sum zero as possible, which
 * contains the subset-sum problem. Greedy is not always minimal; for balances +5, +6, +2, -6, -7 it
 * suggests 4 payments where 3 are enough ({+6, -6} and {+5, +2, -7}). Compared with a brute-force
 * optimum on random groups of 2-7 members, greedy was minimal in 95% of the cases with small whole
 * amounts (-10..10, where exactly cancelling subsets are common) and in 99.7% with cent amounts up
 * to 10 EUR (SettlementPlannerTest). It is simple, fast and predictable, which matters more for a
 * group of friends than occasionally saving one bank transfer.
 */
final class SettlementPlanner {

    record Transfer(long fromMemberId, long toMemberId, long amountCents) {
    }

    private record Position(long memberId, long cents) {
    }

    private SettlementPlanner() {
    }

    static List<Transfer> plan(List<MemberBalance> balances) {
        // Largest amount first; ties go to the lower member id, so the plan is deterministic.
        Comparator<Position> largestFirst = Comparator.comparingLong(Position::cents).reversed()
                .thenComparingLong(Position::memberId);
        PriorityQueue<Position> creditors = new PriorityQueue<>(largestFirst);
        PriorityQueue<Position> debtors = new PriorityQueue<>(largestFirst);
        long sum = 0;
        for (MemberBalance balance : balances) {
            sum += balance.balanceCents();
            if (balance.balanceCents() > 0) {
                creditors.add(new Position(balance.memberId(), balance.balanceCents()));
            } else if (balance.balanceCents() < 0) {
                debtors.add(new Position(balance.memberId(), -balance.balanceCents()));
            }
        }
        if (sum != 0) {
            throw new IllegalStateException("Balances sum to " + sum + " instead of 0; the ledger is inconsistent");
        }

        List<Transfer> transfers = new ArrayList<>();
        while (!creditors.isEmpty()) {
            Position creditor = creditors.poll();
            Position debtor = debtors.poll();
            long amount = Math.min(creditor.cents(), debtor.cents());
            transfers.add(new Transfer(debtor.memberId(), creditor.memberId(), amount));
            if (creditor.cents() > amount) {
                creditors.add(new Position(creditor.memberId(), creditor.cents() - amount));
            }
            if (debtor.cents() > amount) {
                debtors.add(new Position(debtor.memberId(), debtor.cents() - amount));
            }
        }
        return transfers;
    }
}
