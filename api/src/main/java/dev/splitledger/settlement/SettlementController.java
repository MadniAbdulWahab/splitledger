package dev.splitledger.settlement;

import dev.splitledger.common.IdempotentCreator;
import dev.splitledger.group.GroupService;
import dev.splitledger.security.CurrentUser;
import dev.splitledger.settlement.SettlementDtos.DebtsResponse;
import dev.splitledger.settlement.SettlementDtos.PaymentResponse;
import dev.splitledger.settlement.SettlementDtos.RecordPaymentRequest;
import dev.splitledger.settlement.SettlementDtos.SettlementResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
class SettlementController {

    private final SettlementService settlementService;
    private final GroupService groupService;

    SettlementController(SettlementService settlementService, GroupService groupService) {
        this.settlementService = settlementService;
        this.groupService = groupService;
    }

    /** Who owes whom, pair by pair ("Madni owes Lucy 10,00 €"). */
    @GetMapping("/groups/{groupId}/debts")
    DebtsResponse debts(@PathVariable long groupId) {
        return settlementService.debts(groupId);
    }

    /** The fewest transfers that bring every balance to zero. */
    @GetMapping("/groups/{groupId}/settlement")
    SettlementResponse suggest(@PathVariable long groupId) {
        return settlementService.suggest(groupId);
    }

    /**
     * PNG to scan with a German banking app: receiver, IBAN, amount and transfer text filled in.
     * {@code amount} in cents; without it, the open debt from {@code from} to {@code to} is used.
     */
    @GetMapping(path = "/groups/{groupId}/settlement/girocode", produces = MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<byte[]> giroCode(@PathVariable long groupId, @RequestParam long from, @RequestParam long to,
                                    @RequestParam(required = false) @Positive @Max(100_000_000L) Long amount) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore()) // contains an IBAN and the current amount
                .body(settlementService.giroCode(groupId, from, to, amount));
    }

    @PostMapping("/groups/{groupId}/payments")
    ResponseEntity<PaymentResponse> record(@PathVariable long groupId,
                                           @AuthenticationPrincipal CurrentUser user,
                                           @RequestHeader("Idempotency-Key") String idempotencyKey,
                                           @Valid @RequestBody RecordPaymentRequest request) {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be 1 to 100 characters");
        }
        IdempotentCreator.Result<PaymentResponse> result = settlementService.record(groupId, idempotencyKey, request,
                groupService.memberIdOf(groupId, user));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(result.value());
    }

    @GetMapping("/groups/{groupId}/payments")
    List<PaymentResponse> list(@PathVariable long groupId) {
        return settlementService.list(groupId);
    }

    /** Undo a payment recorded by mistake; it stays in the list, marked as undone and by whom. */
    @PostMapping("/groups/{groupId}/payments/{paymentId}/reversal")
    PaymentResponse undo(@PathVariable long groupId, @PathVariable long paymentId,
                         @AuthenticationPrincipal CurrentUser user) {
        return settlementService.undo(groupId, paymentId, groupService.memberIdOf(groupId, user));
    }
}
