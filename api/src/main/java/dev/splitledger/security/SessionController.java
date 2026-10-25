package dev.splitledger.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signing in and out. A session token is sent like an API token ({@code Authorization: Bearer
 * slt_...}); it only differs in that it can be revoked and expires.
 */
@RestController
class SessionController {

    private final UserService userService;

    SessionController(UserService userService) {
        this.userService = userService;
    }

    record LoginRequest(@NotBlank @Size(max = 30) String username, @NotBlank @Size(max = 200) String password) {
    }

    record SessionResponse(String token) {
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    SessionResponse login(@Valid @RequestBody LoginRequest request) {
        return new SessionResponse(userService.login(request.username(), request.password()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@AuthenticationPrincipal CurrentUser user) {
        userService.logout(user);
    }

    /** "Sign out everywhere", e.g. after losing a phone. */
    @PostMapping("/me/sessions/revocation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logoutEverywhere(@AuthenticationPrincipal CurrentUser user) {
        userService.logoutEverywhere(user);
    }
}
