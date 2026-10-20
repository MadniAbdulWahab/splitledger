package dev.splitledger.group;

import dev.splitledger.group.GroupDtos.AddMemberRequest;
import dev.splitledger.group.GroupDtos.CreateGroupRequest;
import dev.splitledger.group.GroupDtos.GroupResponse;
import dev.splitledger.group.GroupDtos.MemberResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/groups")
class GroupController {

    private final GroupService groupService;

    GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    @PostMapping
    ResponseEntity<GroupResponse> createGroup(@Valid @RequestBody CreateGroupRequest request) {
        GroupResponse group = groupService.createGroup(request.name());
        return ResponseEntity.created(location(group.id())).body(group);
    }

    @GetMapping("/{groupId}")
    GroupResponse getGroup(@PathVariable long groupId) {
        return groupService.getGroup(groupId);
    }

    @PostMapping("/{groupId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    MemberResponse addMember(@PathVariable long groupId, @Valid @RequestBody AddMemberRequest request) {
        return groupService.addMember(groupId, request);
    }

    @GetMapping("/{groupId}/members")
    List<MemberResponse> listMembers(@PathVariable long groupId) {
        return groupService.listMembers(groupId);
    }

    private static URI location(long id) {
        return ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(id).toUri();
    }
}
