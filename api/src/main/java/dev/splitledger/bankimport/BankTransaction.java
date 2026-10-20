package dev.splitledger.bankimport;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Read and updated through JPA; inserted with plain SQL (ON CONFLICT) by {@link BankImportService}. */
@Entity
@Table(name = "bank_transaction")
class BankTransaction {

    enum Status { CANDIDATE, CONFIRMED, IGNORED }

    @Id
    private Long id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private Long groupId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "booking_date", nullable = false, updatable = false)
    private LocalDate bookingDate;

    @Column(name = "amount_cents", nullable = false, updatable = false)
    private long amountCents;

    @Column(name = "counterparty_name")
    private String counterpartyName;

    @Column(name = "counterparty_iban", length = 34)
    private String counterpartyIban;

    private String purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(name = "expense_id")
    private Long expenseId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected BankTransaction() {
    }

    void confirm(long expenseId) {
        this.status = Status.CONFIRMED;
        this.expenseId = expenseId;
        this.decidedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Data minimization: an ignored transaction is not a shared expense, so the counterparty and
     * purpose (often private: rent, salary, doctor) are deleted. The fingerprint, date and amount
     * stay, so a later re-import still recognizes the row and does not offer it again.
     */
    void ignore() {
        this.status = Status.IGNORED;
        this.counterpartyName = null;
        this.counterpartyIban = null;
        this.purpose = null;
        this.decidedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    Long getId() {
        return id;
    }

    Long getGroupId() {
        return groupId;
    }

    Long getMemberId() {
        return memberId;
    }

    LocalDate getBookingDate() {
        return bookingDate;
    }

    long getAmountCents() {
        return amountCents;
    }

    String getCounterpartyName() {
        return counterpartyName;
    }

    String getCounterpartyIban() {
        return counterpartyIban;
    }

    String getPurpose() {
        return purpose;
    }

    Status getStatus() {
        return status;
    }

    Long getExpenseId() {
        return expenseId;
    }
}
