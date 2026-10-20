package dev.splitledger.bankimport;

import dev.splitledger.bankimport.BankImportDtos.BankTransactionResponse;
import dev.splitledger.bankimport.BankImportDtos.ConfirmRequest;
import dev.splitledger.bankimport.BankImportDtos.ImportReport;
import dev.splitledger.expense.ExpenseDtos.ExpenseResponse;
import dev.splitledger.group.GroupService;
import dev.splitledger.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/** The statement always belongs to the caller: their member in the group is the account owner. */
@RestController
class BankImportController {

    private final BankImportService importService;
    private final GroupService groupService;

    BankImportController(BankImportService importService, GroupService groupService) {
        this.importService = importService;
        this.groupService = groupService;
    }

    @PostMapping(path = "/groups/{groupId}/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ImportReport importStatement(@PathVariable long groupId, @AuthenticationPrincipal CurrentUser user,
                                 @RequestPart("file") MultipartFile file,
                                 @RequestParam(defaultValue = "SPARKASSE_CSV_CAMT") StatementFormat format) throws IOException {
        long me = groupService.memberIdOf(groupId, user);
        return importService.importStatement(groupId, me, format, file.getBytes());
    }

    @GetMapping("/groups/{groupId}/bank-transactions")
    List<BankTransactionResponse> list(@PathVariable long groupId, @AuthenticationPrincipal CurrentUser user,
                                       @RequestParam(defaultValue = "CANDIDATE") BankTransaction.Status status) {
        return importService.list(groupId, groupService.memberIdOf(groupId, user), status);
    }

    @PostMapping("/groups/{groupId}/bank-transactions/{transactionId}/confirmation")
    @ResponseStatus(HttpStatus.CREATED)
    ExpenseResponse confirm(@PathVariable long groupId, @PathVariable long transactionId,
                            @AuthenticationPrincipal CurrentUser user, @Valid @RequestBody ConfirmRequest request) {
        return importService.confirm(groupId, groupService.memberIdOf(groupId, user), transactionId, request);
    }

    @PostMapping("/groups/{groupId}/bank-transactions/{transactionId}/ignore")
    BankTransactionResponse ignore(@PathVariable long groupId, @PathVariable long transactionId,
                                   @AuthenticationPrincipal CurrentUser user) {
        return importService.ignore(groupId, groupService.memberIdOf(groupId, user), transactionId);
    }
}
