package dev.splitledger.expense;

import dev.splitledger.common.BusinessRuleException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToLongFunction;
import java.util.stream.IntStream;

/**
 * Splits an amount in cents between participants. Pure function, no Spring, so the rounding rule
 * can be tested exhaustively.
 *
 * <p>Remainder rule (largest remainder method): every participant first gets
 * floor(amount * weight / totalWeight). The cents left over (always fewer than the number of
 * participants) go one each to the participants with the largest dropped fraction; ties go to the
 * lower member id. Example: 1000 cents over members 7, 3, 5 gives 3 -> 334, 5 -> 333, 7 -> 333.
 */
public final class SplitCalculator {

    static final long MAX_SHARE_UNITS = 1_000;

    public record Participant(long memberId, Long value) {
    }

    public record Allocation(long memberId, long amountCents, Integer shareUnits) {
    }

    private SplitCalculator() {
    }

    public static List<Allocation> split(long amountCents, SplitMode mode, List<Participant> participants) {
        if (amountCents <= 0) {
            throw new BusinessRuleException("Amount must be positive");
        }
        if (participants.isEmpty()) {
            throw new BusinessRuleException("An expense needs at least one participant");
        }
        List<Participant> byMemberId = participants.stream()
                .sorted(Comparator.comparingLong(Participant::memberId))
                .toList();
        requireDistinctMembers(byMemberId);

        return switch (mode) {
            case EQUAL -> {
                if (byMemberId.stream().anyMatch(p -> p.value() != null)) {
                    throw new BusinessRuleException("EQUAL split takes no participant values");
                }
                yield proportional(amountCents, byMemberId, p -> 1L, false);
            }
            case SHARES -> {
                requireValuesBetween(byMemberId, 1, MAX_SHARE_UNITS, "share units");
                yield proportional(amountCents, byMemberId, Participant::value, true);
            }
            case EXACT -> exact(amountCents, byMemberId);
        };
    }

    private static List<Allocation> proportional(long amountCents, List<Participant> participants,
                                                 ToLongFunction<Participant> weightOf, boolean keepUnits) {
        int n = participants.size();
        long totalWeight = participants.stream().mapToLong(weightOf).sum();
        long[] amounts = new long[n];
        long[] droppedFractions = new long[n];
        long allocated = 0;
        for (int i = 0; i < n; i++) {
            long numerator = Math.multiplyExact(amountCents, weightOf.applyAsLong(participants.get(i)));
            amounts[i] = numerator / totalWeight;
            droppedFractions[i] = numerator % totalWeight;
            allocated += amounts[i];
        }

        long leftoverCents = amountCents - allocated;
        List<Integer> order = IntStream.range(0, n).boxed()
                .sorted(Comparator.<Integer>comparingLong(i -> droppedFractions[i]).reversed()
                        .thenComparingLong(i -> participants.get(i).memberId()))
                .toList();
        for (int k = 0; k < leftoverCents; k++) {
            amounts[order.get(k)]++;
        }

        List<Allocation> allocations = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Participant p = participants.get(i);
            Integer units = keepUnits ? Math.toIntExact(p.value()) : null;
            allocations.add(new Allocation(p.memberId(), amounts[i], units));
        }
        return allocations;
    }

    private static List<Allocation> exact(long amountCents, List<Participant> participants) {
        requireValuesBetween(participants, 1, amountCents, "exact amounts");
        long sum = participants.stream().mapToLong(Participant::value).sum();
        if (sum != amountCents) {
            throw new BusinessRuleException(
                    "Exact amounts add up to " + sum + " cents, but the expense is " + amountCents + " cents");
        }
        return participants.stream()
                .map(p -> new Allocation(p.memberId(), p.value(), null))
                .toList();
    }

    private static void requireDistinctMembers(List<Participant> sortedParticipants) {
        for (int i = 1; i < sortedParticipants.size(); i++) {
            if (sortedParticipants.get(i).memberId() == sortedParticipants.get(i - 1).memberId()) {
                throw new BusinessRuleException(
                        "Member " + sortedParticipants.get(i).memberId() + " is listed more than once");
            }
        }
    }

    private static void requireValuesBetween(List<Participant> participants, long min, long max, String what) {
        for (Participant p : participants) {
            if (p.value() == null || p.value() < min || p.value() > max) {
                throw new BusinessRuleException(
                        "Member " + p.memberId() + ": " + what + " must be between " + min + " and " + max);
            }
        }
    }
}
