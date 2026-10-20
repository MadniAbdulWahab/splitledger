package dev.splitledger.group;

import dev.splitledger.common.ConflictException;
import dev.splitledger.common.Iban;
import dev.splitledger.common.NotFoundException;
import dev.splitledger.common.SecretCodes;
import dev.splitledger.group.GroupDtos.AddMemberRequest;
import dev.splitledger.group.GroupDtos.AddedMemberResponse;
import dev.splitledger.group.GroupDtos.GroupResponse;
import dev.splitledger.group.GroupDtos.MemberResponse;
import dev.splitledger.group.GroupDtos.MembershipResponse;
import dev.splitledger.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class GroupService {

    static final String INVITE_PREFIX = "inv_";

    private final ExpenseGroupRepository groups;
    private final MemberRepository members;

    GroupService(ExpenseGroupRepository groups, MemberRepository members) {
        this.groups = groups;
        this.members = members;
    }

    /** The creator becomes the first member, so they can access the group right away. */
    @Transactional
    public GroupResponse createGroup(String name, String displayName, CurrentUser creator) {
        ExpenseGroup group = groups.save(new ExpenseGroup(name.strip()));
        String memberName = displayName == null || displayName.isBlank() ? creator.displayName() : displayName.strip();
        members.save(Member.forUser(group.getId(), memberName, creator.id()));
        return GroupResponse.from(group);
    }

    @Transactional(readOnly = true)
    public GroupResponse getGroup(long groupId) {
        return groups.findById(groupId)
                .map(GroupResponse::from)
                .orElseThrow(() -> groupNotFound(groupId));
    }

    /** Adds a friend as a member and returns a one-time invite code that links their account later. */
    @Transactional
    public AddedMemberResponse addMember(long groupId, AddMemberRequest request) {
        requireGroup(groupId);
        String displayName = request.displayName().strip();
        if (members.existsByGroupIdAndDisplayName(groupId, displayName)) {
            throw new ConflictException("Group " + groupId + " already has a member called '" + displayName + "'");
        }
        String iban = request.iban() == null ? null : Iban.normalize(request.iban());
        String inviteCode = SecretCodes.generate(INVITE_PREFIX);
        Member member = members.save(Member.invited(groupId, displayName, iban, request.bic(), SecretCodes.hash(inviteCode)));
        return new AddedMemberResponse(MemberResponse.from(member), inviteCode);
    }

    @Transactional
    public MembershipResponse acceptInvitation(String code, CurrentUser user) {
        Member member = members.findByInviteCodeHash(SecretCodes.hash(code.strip()))
                .orElseThrow(() -> new NotFoundException("Invitation code is unknown or already used"));
        if (members.existsByGroupIdAndUserId(member.getGroupId(), user.id())) {
            throw new ConflictException("You are already a member of group " + member.getGroupId());
        }
        member.acceptInvitation(user.id());
        ExpenseGroup group = groups.getReferenceById(member.getGroupId());
        return new MembershipResponse(group.getId(), group.getName(), member.getId(), member.getDisplayName());
    }

    @Transactional(readOnly = true)
    public List<MembershipResponse> membershipsOf(long userId) {
        List<Member> memberships = members.findByUserIdOrderByGroupId(userId);
        Map<Long, ExpenseGroup> groupsById = groups.findAllById(memberships.stream().map(Member::getGroupId).toList())
                .stream().collect(Collectors.toMap(ExpenseGroup::getId, Function.identity()));
        return memberships.stream()
                .map(m -> new MembershipResponse(m.getGroupId(), groupsById.get(m.getGroupId()).getName(),
                        m.getId(), m.getDisplayName()))
                .toList();
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

    @Transactional(readOnly = true)
    public boolean isMember(long groupId, long userId) {
        return members.existsByGroupIdAndUserId(groupId, userId);
    }

    /** The caller's member id in the group. Access was already checked by the security rules. */
    @Transactional(readOnly = true)
    public long memberIdOf(long groupId, CurrentUser user) {
        return members.findByGroupIdAndUserId(groupId, user.id())
                .map(Member::getId)
                .orElseThrow(() -> new NotFoundException("You are not a member of group " + groupId));
    }

    @Transactional(readOnly = true)
    public Optional<Member> member(long memberId) {
        return members.findById(memberId);
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
