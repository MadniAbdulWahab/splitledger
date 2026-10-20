package dev.splitledger.group;

import dev.splitledger.group.GroupDtos.AcceptInvitationRequest;
import dev.splitledger.group.GroupDtos.AddMemberRequest;
import dev.splitledger.group.GroupDtos.AddedMemberResponse;
import dev.splitledger.group.GroupDtos.BankDetailsRequest;
import dev.splitledger.group.GroupDtos.CreateGroupRequest;
import dev.splitledger.group.GroupDtos.GroupResponse;
import dev.splitledger.group.GroupDtos.MemberResponse;
import dev.splitledger.group.GroupDtos.MembershipResponse;
import dev.splitledger.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

/** Access to /groups/{groupId}/** is checked centrally by {@link GroupMembershipAuthorization}. */
@RestController
class GroupController {

    private final GroupService groupService;

    GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    @PostMapping("/groups")
    ResponseEntity<GroupResponse> createGroup(@AuthenticationPrincipal CurrentUser user,
                                              @Valid @RequestBody CreateGroupRequest request) {
        GroupResponse group = groupService.createGroup(request.name(), request.displayName(), user);
        return ResponseEntity
                .created(ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(group.id()).toUri())
                .body(group);
    }

    @GetMapping("/groups/{groupId}")
    GroupResponse getGroup(@PathVariable long groupId) {
        return groupService.getGroup(groupId);
    }

    @PostMapping("/groups/{groupId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    AddedMemberResponse addMember(@PathVariable long groupId, @Valid @RequestBody AddMemberRequest request) {
        return groupService.addMember(groupId, request);
    }

    @GetMapping("/groups/{groupId}/members")
    List<MemberResponse> listMembers(@PathVariable long groupId) {
        return groupService.listMembers(groupId);
    }

    @PutMapping("/groups/{groupId}/members/me/bank-details")
    MemberResponse updateOwnBankDetails(@PathVariable long groupId, @AuthenticationPrincipal CurrentUser user,
                                        @Valid @RequestBody BankDetailsRequest request) {
        return groupService.updateOwnBankDetails(groupId, user, request);
    }

    @PostMapping("/invitations/accept")
    MembershipResponse acceptInvitation(@AuthenticationPrincipal CurrentUser user,
                                        @Valid @RequestBody AcceptInvitationRequest request) {
        return groupService.acceptInvitation(request.code(), user);
    }
}
