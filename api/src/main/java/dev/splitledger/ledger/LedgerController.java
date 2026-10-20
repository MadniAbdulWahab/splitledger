package dev.splitledger.ledger;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
class LedgerController {

    private final LedgerService ledgerService;

    LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping("/groups/{groupId}/balances")
    BalancesResponse balances(@PathVariable long groupId) {
        return new BalancesResponse(groupId, ledgerService.balances(groupId));
    }

    @GetMapping("/groups/{groupId}/verify")
    ChainVerification verify(@PathVariable long groupId) {
        return ledgerService.verify(groupId);
    }

    record BalancesResponse(long groupId, List<MemberBalance> balances) {
    }
}
