package dev.splitledger.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Reset codes travel in request bodies only; URL paths end up in the request log. */
@RestController
class PasswordResetController {

    private final PasswordResetService resets;

    PasswordResetController(PasswordResetService resets) {
        this.resets = resets;
    }

    record CodeRequest(@NotBlank @Size(max = 100) String code) {
    }

    record RedeemRequest(@NotBlank @Size(max = 100) String code, @Size(max = 30) String username,
                         @NotBlank @Size(max = 200) String newPassword) {
    }

    /** Group membership is checked by the central rule for /groups/{groupId}/**. */
    @PostMapping("/groups/{groupId}/members/{memberId}/password-reset")
    @ResponseStatus(HttpStatus.CREATED)
    PasswordResetService.ResetLink create(@PathVariable long groupId, @PathVariable long memberId,
                                          @AuthenticationPrincipal CurrentUser user) {
        return resets.create(groupId, memberId, user);
    }

    @PostMapping("/password-resets/preview")
    PasswordResetService.ResetPreview preview(@Valid @RequestBody CodeRequest request) {
        return resets.preview(request.code());
    }

    @PostMapping("/password-resets")
    UserService.Registration redeem(@Valid @RequestBody RedeemRequest request) {
        return resets.redeem(request.code(), request.username(), request.newPassword());
    }
}
