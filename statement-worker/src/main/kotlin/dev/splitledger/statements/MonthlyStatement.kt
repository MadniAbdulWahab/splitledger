package dev.splitledger.statements

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth

/**
 * Summary of one group's month, stored as JSON in S3 and updated event by event.
 *
 * Amounts are integer cents, like everywhere in SplitLedger. A reversal subtracts what its
 * expense added and undoing a payment subtracts the payment, so the month shows what is still valid.
 */
@Serializable
data class MonthlyStatement(
    val groupId: Long,
    val month: String,
    val expenseCount: Int = 0,
    val reversedExpenseCount: Int = 0,
    /** Sum of the month's expenses minus reversed ones. */
    val expensesCents: Long = 0,
    val paymentCount: Int = 0,
    val undonePaymentCount: Int = 0,
    /** Sum of the month's payments minus undone ones. */
    val paymentsCents: Long = 0,
    val members: List<MemberLine> = emptyList(),
    /** Highest event applied; events arrive in order per group, so anything not newer is a repeat. */
    val lastEventId: Long = 0,
) {

    fun alreadyContains(event: LedgerEvent): Boolean = event.eventId <= lastEventId

    /** Returns the statement including [event]. Pure: no I/O, so every rule is unit-testable. */
    fun apply(event: LedgerEvent): MonthlyStatement {
        require(event.groupId == groupId && monthOf(event) == month) {
            "Event ${event.eventId} belongs to group ${event.groupId}, ${monthOf(event)}, not $groupId, $month"
        }
        require(!alreadyContains(event)) { "Event ${event.eventId} was already applied" }

        val byMember = members.associateBy { it.memberId }.toMutableMap()
        for (posting in event.postings) {
            val line = byMember[posting.memberId] ?: MemberLine(posting.memberId, posting.displayName)
            byMember[posting.memberId] = line.with(event.type, posting)
        }
        val type = event.type
        return copy(
            expenseCount = expenseCount + if (type == EventType.EXPENSE_RECORDED) 1 else 0,
            reversedExpenseCount = reversedExpenseCount + if (type == EventType.EXPENSE_REVERSED) 1 else 0,
            expensesCents = expensesCents + when (type) {
                EventType.EXPENSE_RECORDED -> event.amountCents
                EventType.EXPENSE_REVERSED -> -event.amountCents
                else -> 0
            },
            paymentCount = paymentCount + if (type == EventType.PAYMENT_RECORDED) 1 else 0,
            undonePaymentCount = undonePaymentCount + if (type == EventType.PAYMENT_REVERSED) 1 else 0,
            paymentsCents = paymentsCents + when (type) {
                EventType.PAYMENT_RECORDED -> event.amountCents
                EventType.PAYMENT_REVERSED -> -event.amountCents
                else -> 0
            },
            members = byMember.values.sortedBy { it.memberId },
            lastEventId = event.eventId,
        )
    }

    companion object {
        fun monthOf(event: LedgerEvent): String = YearMonth.from(LocalDate.parse(event.occurredOn)).toString()

        fun emptyFor(event: LedgerEvent) = MonthlyStatement(groupId = event.groupId, month = monthOf(event))
    }
}

/**
 * One member's month. Postings are the ledger's signed entries: on an expense the payer gets a
 * positive entry and every participant (the payer too, if they took part) a negative one.
 */
@Serializable
data class MemberLine(
    val memberId: Long,
    val displayName: String? = null,
    /** What this member paid for the group. */
    val paidCents: Long = 0,
    /** This member's share of the group's expenses. */
    val shareCents: Long = 0,
    val paymentsSentCents: Long = 0,
    val paymentsReceivedCents: Long = 0,
    /** Change of the member's balance in this month; positive means the group owes them more. */
    val netChangeCents: Long = 0,
) {
    fun with(type: EventType, posting: MemberPosting): MemberLine {
        val cents = posting.amountCents
        val updated = when (type) {
            EventType.EXPENSE_RECORDED ->
                if (cents > 0) copy(paidCents = paidCents + cents) else copy(shareCents = shareCents - cents)
            // A reversal mirrors its expense entry by entry: -paid for the payer, +share for participants.
            EventType.EXPENSE_REVERSED ->
                if (cents < 0) copy(paidCents = paidCents + cents) else copy(shareCents = shareCents - cents)
            EventType.PAYMENT_RECORDED ->
                if (cents > 0) copy(paymentsSentCents = paymentsSentCents + cents)
                else copy(paymentsReceivedCents = paymentsReceivedCents - cents)
            // Undoing mirrors the payment: -sent for the sender, -received for the receiver.
            EventType.PAYMENT_REVERSED ->
                if (cents < 0) copy(paymentsSentCents = paymentsSentCents + cents)
                else copy(paymentsReceivedCents = paymentsReceivedCents - cents)
        }
        return updated.copy(
            displayName = posting.displayName ?: displayName,
            netChangeCents = netChangeCents + cents,
        )
    }
}
