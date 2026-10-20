package dev.splitledger.group;

import dev.splitledger.common.ValidIban;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Request and response bodies of the group endpoints. */
public final class GroupDtos {

    private GroupDtos() {
    }

    /** {@code displayName}: the creator's name inside this group; defaults to the account name. */
    public record CreateGroupRequest(@NotBlank @Size(max = 100) String name, @Size(max = 80) String displayName) {
    }

    public record GroupResponse(long id, String name, Instant createdAt) {
        static GroupResponse from(ExpenseGroup group) {
            return new GroupResponse(group.getId(), group.getName(), group.getCreatedAt());
        }
    }

    public record AddMemberRequest(
            @NotBlank @Size(max = 80) String displayName,
            @ValidIban String iban,
            // 8 or 11 characters: bank code, country, location and optional branch.
            @Pattern(regexp = "[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?", message = "is not a valid BIC") String bic) {
    }

    /** Both null removes the bank details. */
    public record BankDetailsRequest(
            @ValidIban String iban,
            @Pattern(regexp = "[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?", message = "is not a valid BIC") String bic) {
    }

    /** {@code joined}: whether a friend's account is linked to this member yet. */
    public record MemberResponse(long id, String displayName, String iban, String bic, boolean joined) {
        static MemberResponse from(Member member) {
            return new MemberResponse(member.getId(), member.getDisplayName(), member.getIban(), member.getBic(),
                    member.getUserId() != null);
        }
    }

    /** Returned once when a member is added: the invite code to pass to the friend. */
    public record AddedMemberResponse(MemberResponse member, String inviteCode) {
    }

    public record AcceptInvitationRequest(@NotBlank String code) {
    }

    public record MembershipResponse(long groupId, String groupName, long memberId, String displayName) {
    }
}
