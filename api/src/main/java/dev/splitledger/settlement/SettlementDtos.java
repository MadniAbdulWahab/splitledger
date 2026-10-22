package dev.splitledger.settlement;

import dev.splitledger.common.CanonicalText;
import dev.splitledger.ledger.CorrectionLog;
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

    /** {@code undoneBy}/{@code undoneAt} are set when the payment was undone (then {@code reversed}). */
    public record PaymentResponse(long id, long groupId, long fromMemberId, long toMemberId, long amountCents,
                                  String note, Instant createdAt, boolean reversed, Long undoneBy, Instant undoneAt) {
        static PaymentResponse from(Payment p, CorrectionLog.Entry undone) {
            return new PaymentResponse(p.getId(), p.getGroupId(), p.getFromMemberId(), p.getToMemberId(),
                    p.getAmountCents(), p.getNote(), p.getCreatedAt(), undone != null,
                    undone != null ? undone.memberId() : null, undone != null ? undone.at() : null);
        }
    }
}
