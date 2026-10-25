package dev.splitledger.security;

import dev.splitledger.group.GroupDtos.MembershipResponse;
import dev.splitledger.group.GroupService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@RestController
class UserController {

    private final UserService userService;
    private final GroupService groupService;
    private final String registrationCode;

    /**
     * {@code splitledger.registration-code}: when set (e.g. on a public host), registering needs the
     * header {@code X-Registration-Code} with this value, so only invited friends can create accounts.
     * Friends who open an invite link do not need it (see {@code POST /invitations/signup}).
     */
    UserController(UserService userService, GroupService groupService,
                   @Value("${splitledger.registration-code:}") String registrationCode) {
        this.userService = userService;
        this.groupService = groupService;
        this.registrationCode = registrationCode;
    }

    /** Without username and password the account gets an API token (scripts, API docs). */
    record RegisterRequest(@NotBlank @Size(max = 80) String displayName,
                           @Size(max = 30) String username,
                           @Size(max = 200) String password) {
    }

    record MeResponse(long id, String displayName, String username, boolean hasPassword,
                      List<MembershipResponse> groups) {
    }

    record UsernameRequest(@NotBlank @Size(max = 30) String username) {
    }

    record PasswordRequest(@Size(max = 200) String currentPassword, @NotBlank @Size(max = 200) String newPassword) {
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    UserService.Registration register(@RequestHeader(value = "X-Registration-Code", required = false) String code,
                                      @Valid @RequestBody RegisterRequest request) {
        if (!registrationCode.isEmpty() && !constantTimeEquals(registrationCode, code)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Registration needs a valid X-Registration-Code header");
        }
        if (request.username() == null && request.password() == null) {
            return userService.register(request.displayName());
        }
        if (request.username() == null || request.password() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send both username and password, or neither");
        }
        return userService.register(request.displayName(), request.username(), request.password());
    }

    record InvitationSignupRequest(@NotBlank @Size(max = 100) String code, @NotBlank @Size(max = 30) String username,
                                   @NotBlank @Size(max = 200) String password) {
    }

    /** Opening an invite link without an account: no registration code, the invite is the permission. */
    @PostMapping("/invitations/signup")
    @ResponseStatus(HttpStatus.CREATED)
    UserService.Registration signUpWithInvitation(@Valid @RequestBody InvitationSignupRequest request) {
        return userService.signUpWithInvitation(request.code(), request.username(), request.password());
    }

    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal CurrentUser user) {
        UserService.Account account = userService.account(user.id());
        return new MeResponse(user.id(), user.displayName(), account.username(), account.hasPassword(),
                groupService.membershipsOf(user.id()));
    }

    @PutMapping("/me/username")
    UserService.Account changeUsername(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody UsernameRequest request) {
        return userService.changeUsername(user, request.username());
    }

    /** Signs out every other device of the user. */
    @PutMapping("/me/password")
    UserService.Account changePassword(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody PasswordRequest request) {
        return userService.changePassword(user, request.currentPassword(), request.newPassword());
    }

    // Compares in constant time, so response timing does not reveal how much of the code was right.
    private static boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
