package dev.splitledger.settlement;

import dev.splitledger.common.CanonicalText;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class SettlementDtos {

    private SettlementDtos() {
    }

    /** One suggested transfer, with the receiver's bank details (needed for the GiroCode on day 7). */
    public record SuggestedPayment(long fromMemberId, String fromName, long toMemberId, String toName,
                                   String toIban, String toBic, long amountCents) {
    }

    public record SettlementResponse(long groupId, List<SuggestedPayment> payments) {
    }

    /** Open debts per pair of members, largest first. */
    public record DebtsResponse(long groupId, List<SuggestedPayment> debts) {
    }

    public record RecordPaymentRequest(
            @NotNull Long fromMemberId,
            @NotNull Long toMemberId,
            @NotNull @Positive @Max(100_000_000L) Long amountCents,
            @Size(max = 200) String note) {

        String canonicalForm() {
            return CanonicalText.of(fromMemberId, toMemberId, amountCents, note);
        }
    }

    public record PaymentResponse(long id, long groupId, long fromMemberId, long toMemberId, long amountCents,
                                  String note, Instant createdAt) {
        static PaymentResponse from(Payment p) {
            return new PaymentResponse(p.getId(), p.getGroupId(), p.getFromMemberId(), p.getToMemberId(),
                    p.getAmountCents(), p.getNote(), p.getCreatedAt());
        }
    }
}
