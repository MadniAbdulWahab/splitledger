package dev.splitledger.group;

import dev.splitledger.security.CurrentUser;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * The one access rule for everything under /groups/{groupId}: the caller must be a member.
 * It runs in the security filter chain before any controller, so a new endpoint under that path
 * is protected without anyone remembering to add a check.
 *
 * <p>A missing group and a group the caller is not in both get 403, so the answer does not reveal
 * which group ids exist.
 */
@Component
class GroupMembershipAuthorization implements AuthorizationManager<RequestAuthorizationContext> {

    private final GroupService groupService;

    GroupMembershipAuthorization(GroupService groupService) {
        this.groupService = groupService;
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                                         RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !(auth.getPrincipal() instanceof CurrentUser user)) {
            return new AuthorizationDecision(false);
        }
        try {
            long groupId = Long.parseLong(context.getVariables().get("groupId"));
            return new AuthorizationDecision(groupService.isMember(groupId, user.id()));
        } catch (NumberFormatException e) {
            return new AuthorizationDecision(false);
        }
    }
}
