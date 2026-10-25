package dev.splitledger.expense;

import dev.splitledger.expense.SplitCalculator.Allocation;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "expense")
public class Expense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private Long groupId;

    @Column(name = "payer_id", nullable = false, updatable = false)
    private Long payerId;

    @Column(name = "amount_cents", nullable = false, updatable = false)
    private long amountCents;

    @Column(nullable = false, length = 200)
    private String description;

    private String notes;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "split_mode", nullable = false, length = 10)
    private SplitMode splitMode;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Purpose text of the confirmed bank payment this expense came from; null for manual expenses. */
    @Column(name = "bank_purpose", updatable = false)
    private String bankPurpose;

    /** Null for expenses from before V11. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, updatable = false)
    private Category category;

    /** The member who entered the expense; null for expenses from before V11. */
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @ElementCollection
    @CollectionTable(name = "expense_share", joinColumns = @JoinColumn(name = "expense_id"))
    @OrderBy("memberId")
    private List<ExpenseShare> shares = new ArrayList<>();

    protected Expense() {
    }

    Expense(Long groupId, Long payerId, long amountCents, String description, String notes,
            LocalDate expenseDate, SplitMode splitMode, String idempotencyKey, String requestHash,
            String bankPurpose, List<Allocation> allocations, Category category, Long createdBy) {
        this.bankPurpose = bankPurpose;
        this.category = category;
        this.createdBy = createdBy;
        this.groupId = groupId;
        this.payerId = payerId;
        this.amountCents = amountCents;
        this.description = description;
        this.notes = notes;
        this.expenseDate = expenseDate;
        this.splitMode = splitMode;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        for (Allocation allocation : allocations) {
            shares.add(new ExpenseShare(allocation.memberId(), groupId, allocation.amountCents(), allocation.shareUnits()));
        }
    }

    public Long getId() {
        return id;
    }

    public Long getGroupId() {
        return groupId;
    }

    public Long getPayerId() {
        return payerId;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public String getDescription() {
        return description;
    }

    public String getNotes() {
        return notes;
    }

    public LocalDate getExpenseDate() {
        return expenseDate;
    }

    public SplitMode getSplitMode() {
        return splitMode;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getBankPurpose() {
        return bankPurpose;
    }

    public List<ExpenseShare> getShares() {
        return List.copyOf(shares);
    }

    public Category getCategory() {
        return category;
    }

    public Long getCreatedBy() {
        return createdBy;
    }
}
