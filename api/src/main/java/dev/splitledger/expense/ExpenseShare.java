package dev.splitledger.expense;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** One participant's part of an expense. A value object owned by {@link Expense}. */
@Embeddable
public class ExpenseShare {

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(name = "share_units")
    private Integer shareUnits;

    protected ExpenseShare() {
    }

    ExpenseShare(Long memberId, Long groupId, long amountCents, Integer shareUnits) {
        this.memberId = memberId;
        this.groupId = groupId;
        this.amountCents = amountCents;
        this.shareUnits = shareUnits;
    }

    public Long getMemberId() {
        return memberId;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public Integer getShareUnits() {
        return shareUnits;
    }
}
