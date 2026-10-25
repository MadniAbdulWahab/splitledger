package dev.splitledger.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "member")
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private Long groupId;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(length = 34)
    private String iban;

    @Column(length = 11)
    private String bic;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** The account behind this member; null until the friend accepts the invitation. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "invite_code_hash", length = 64)
    private String inviteCodeHash;

    /** The member who added this one; null for the group's creator and members added before V10. */
    @Column(name = "invited_by")
    private Long invitedBy;

    /** When the account was linked: at creation for the creator, on accepting the invite otherwise. */
    @Column(name = "joined_at")
    private Instant joinedAt;

    protected Member() {
    }

    Member(Long groupId, String displayName, String iban, String bic) {
        this.groupId = groupId;
        this.displayName = displayName;
        this.iban = iban;
        this.bic = bic;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    static Member forUser(Long groupId, String displayName, long userId) {
        Member member = new Member(groupId, displayName, null, null);
        member.userId = userId;
        member.joinedAt = member.createdAt;
        return member;
    }

    static Member invited(Long groupId, String displayName, String iban, String bic, String inviteCodeHash, long invitedBy) {
        Member member = new Member(groupId, displayName, iban, bic);
        member.inviteCodeHash = inviteCodeHash;
        member.invitedBy = invitedBy;
        return member;
    }

    /** A new invite link replaces the old one, which stops working. */
    void replaceInviteCode(String inviteCodeHash) {
        this.inviteCodeHash = inviteCodeHash;
    }

    void changeBankDetails(String iban, String bic) {
        this.iban = iban;
        this.bic = bic;
    }

    /** Links the account and invalidates the invite code, so it cannot be used a second time. */
    void acceptInvitation(long userId) {
        this.userId = userId;
        this.inviteCodeHash = null;
        this.joinedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Long getUserId() {
        return userId;
    }

    public Long getId() {
        return id;
    }

    public Long getGroupId() {
        return groupId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getIban() {
        return iban;
    }

    public String getBic() {
        return bic;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getInvitedBy() {
        return invitedBy;
    }
}
