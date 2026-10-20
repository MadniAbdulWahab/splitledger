package dev.splitledger.group;

import dev.splitledger.common.ConflictException;
import dev.splitledger.common.Iban;
import dev.splitledger.common.NotFoundException;
import dev.splitledger.group.GroupDtos.AddMemberRequest;
import dev.splitledger.group.GroupDtos.GroupResponse;
import dev.splitledger.group.GroupDtos.MemberResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class GroupService {

    private final ExpenseGroupRepository groups;
    private final MemberRepository members;

    GroupService(ExpenseGroupRepository groups, MemberRepository members) {
        this.groups = groups;
        this.members = members;
    }

    @Transactional
    public GroupResponse createGroup(String name) {
        return GroupResponse.from(groups.save(new ExpenseGroup(name.strip())));
    }

    @Transactional(readOnly = true)
    public GroupResponse getGroup(long groupId) {
        return groups.findById(groupId)
                .map(GroupResponse::from)
                .orElseThrow(() -> groupNotFound(groupId));
    }

    @Transactional
    public MemberResponse addMember(long groupId, AddMemberRequest request) {
        requireGroup(groupId);
        String displayName = request.displayName().strip();
        if (members.existsByGroupIdAndDisplayName(groupId, displayName)) {
            throw new ConflictException("Group " + groupId + " already has a member called '" + displayName + "'");
        }
        String iban = request.iban() == null ? null : Iban.normalize(request.iban());
        return MemberResponse.from(members.save(new Member(groupId, displayName, iban, request.bic())));
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> listMembers(long groupId) {
        requireGroup(groupId);
        return members.findByGroupIdOrderById(groupId).stream().map(MemberResponse::from).toList();
    }

    /** Ids of all members of the group; throws if the group does not exist. */
    @Transactional(readOnly = true)
    public Set<Long> memberIds(long groupId) {
        requireGroup(groupId);
        return members.findIdsByGroupId(groupId);
    }

    public void requireGroup(long groupId) {
        if (!groups.existsById(groupId)) {
            throw groupNotFound(groupId);
        }
    }

    private static NotFoundException groupNotFound(long groupId) {
        return new NotFoundException("Group " + groupId + " does not exist");
    }
}
