package dev.splitledger.expense;

import dev.splitledger.common.BusinessRuleException;
import dev.splitledger.expense.SplitCalculator.Allocation;
import dev.splitledger.expense.SplitCalculator.Participant;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SplitCalculatorTest {

    @Test
    void equalSplitGivesLeftoverCentsToLowestMemberIds() {
        List<Allocation> result = SplitCalculator.split(1000, SplitMode.EQUAL,
                List.of(equal(7), equal(3), equal(5)));

        assertThat(result).containsExactly(
                new Allocation(3, 334, null),
                new Allocation(5, 333, null),
                new Allocation(7, 333, null));
    }

    @Test
    void equalSplitWithTwoLeftoverCents() {
        List<Allocation> result = SplitCalculator.split(1001, SplitMode.EQUAL,
                List.of(equal(1), equal(2), equal(3)));

        assertThat(result).extracting(Allocation::amountCents).containsExactly(334L, 334L, 333L);
    }

    @Test
    void sharesSplitIsProportional() {
        List<Allocation> result = SplitCalculator.split(1000, SplitMode.SHARES,
                List.of(new Participant(1, 2L), new Participant(2, 1L)));

        assertThat(result).containsExactly(
                new Allocation(1, 667, 2),
                new Allocation(2, 333, 1));
    }

    @Test
    void largestDroppedFractionBeatsLowerMemberId() {
        // 10 cents in shares 1:2 -> exact 3.33 and 6.67; member 2 dropped more, so gets the leftover cent.
        List<Allocation> result = SplitCalculator.split(10, SplitMode.SHARES,
                List.of(new Participant(1, 1L), new Participant(2, 2L)));

        assertThat(result).extracting(Allocation::amountCents).containsExactly(3L, 7L);
    }

    @Test
    void exactSplitKeepsGivenAmounts() {
        List<Allocation> result = SplitCalculator.split(1000, SplitMode.EXACT,
                List.of(new Participant(2, 250L), new Participant(1, 750L)));

        assertThat(result).containsExactly(
                new Allocation(1, 750, null),
                new Allocation(2, 250, null));
    }

    @Test
    void exactSplitMustAddUpToTheAmount() {
        assertThatThrownBy(() -> SplitCalculator.split(1000, SplitMode.EXACT,
                List.of(new Participant(1, 500L), new Participant(2, 400L))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("add up to 900 cents");
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> SplitCalculator.split(1000, SplitMode.EQUAL, List.of()))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> SplitCalculator.split(1000, SplitMode.EQUAL, List.of(equal(1), equal(1))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("more than once");
        assertThatThrownBy(() -> SplitCalculator.split(1000, SplitMode.EQUAL, List.of(new Participant(1, 5L))))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> SplitCalculator.split(1000, SplitMode.SHARES, List.of(equal(1))))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> SplitCalculator.split(1000, SplitMode.SHARES, List.of(new Participant(1, 1001L))))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> SplitCalculator.split(0, SplitMode.EQUAL, List.of(equal(1))))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void resultDoesNotDependOnParticipantOrder() {
        List<Participant> participants = new ArrayList<>(List.of(
                new Participant(4, 3L), new Participant(9, 1L), new Participant(2, 5L), new Participant(6, 1L)));
        List<Allocation> expected = SplitCalculator.split(9999, SplitMode.SHARES, participants);

        Random random = new Random(7);
        for (int i = 0; i < 20; i++) {
            Collections.shuffle(participants, random);
            assertThat(SplitCalculator.split(9999, SplitMode.SHARES, participants)).isEqualTo(expected);
        }
    }

    /**
     * Invariants for many random splits: the parts add up to the amount exactly, and every part is
     * the exact proportional value rounded down or up, never further off than one cent.
     */
    @Test
    void randomSplitsAddUpAndStayWithinOneCent() {
        Random random = new Random(42);
        for (int run = 0; run < 10_000; run++) {
            long amount = 1 + random.nextInt(1_000_000);
            int n = 1 + random.nextInt(10);
            boolean shares = random.nextBoolean();
            List<Participant> participants = new ArrayList<>();
            for (int id = 1; id <= n; id++) {
                participants.add(new Participant(id * 10L, shares ? 1L + random.nextInt(5) : null));
            }

            List<Allocation> result = SplitCalculator.split(amount, shares ? SplitMode.SHARES : SplitMode.EQUAL, participants);

            assertThat(result.stream().mapToLong(Allocation::amountCents).sum()).isEqualTo(amount);
            long totalWeight = participants.stream().mapToLong(p -> shares ? p.value() : 1L).sum();
            for (int i = 0; i < n; i++) {
                long weight = shares ? participants.get(i).value() : 1L;
                long floor = amount * weight / totalWeight;
                assertThat(result.get(i).amountCents()).isBetween(floor, floor + 1);
            }
        }
    }

    private static Participant equal(long memberId) {
        return new Participant(memberId, null);
    }
}
