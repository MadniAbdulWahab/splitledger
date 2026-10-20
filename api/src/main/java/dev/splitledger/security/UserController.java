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
     */
    UserController(UserService userService, GroupService groupService,
                   @Value("${splitledger.registration-code:}") String registrationCode) {
        this.userService = userService;
        this.groupService = groupService;
        this.registrationCode = registrationCode;
    }

    record RegisterRequest(@NotBlank @Size(max = 80) String displayName) {
    }

    record MeResponse(long id, String displayName, List<MembershipResponse> groups) {
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    UserService.Registration register(@RequestHeader(value = "X-Registration-Code", required = false) String code,
                                      @Valid @RequestBody RegisterRequest request) {
        if (!registrationCode.isEmpty() && !constantTimeEquals(registrationCode, code)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Registration needs a valid X-Registration-Code header");
        }
        return userService.register(request.displayName());
    }

    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal CurrentUser user) {
        return new MeResponse(user.id(), user.displayName(), groupService.membershipsOf(user.id()));
    }

    // Compares in constant time, so response timing does not reveal how much of the code was right.
    private static boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
