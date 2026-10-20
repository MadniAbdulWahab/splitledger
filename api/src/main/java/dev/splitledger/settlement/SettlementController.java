package dev.splitledger.settlement;

import dev.splitledger.common.IdempotentCreator;
import dev.splitledger.settlement.SettlementDtos.PaymentResponse;
import dev.splitledger.settlement.SettlementDtos.RecordPaymentRequest;
import dev.splitledger.settlement.SettlementDtos.SettlementResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
class SettlementController {

    private final SettlementService settlementService;

    SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /** Who should pay whom so that every balance becomes zero. */
    @GetMapping("/groups/{groupId}/settlement")
    SettlementResponse suggest(@PathVariable long groupId) {
        return settlementService.suggest(groupId);
    }

    @PostMapping("/groups/{groupId}/payments")
    ResponseEntity<PaymentResponse> record(@PathVariable long groupId,
                                           @RequestHeader("Idempotency-Key") String idempotencyKey,
                                           @Valid @RequestBody RecordPaymentRequest request) {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be 1 to 100 characters");
        }
        IdempotentCreator.Result<PaymentResponse> result = settlementService.record(groupId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(result.value());
    }

    @GetMapping("/groups/{groupId}/payments")
    List<PaymentResponse> list(@PathVariable long groupId) {
        return settlementService.list(groupId);
    }
}
