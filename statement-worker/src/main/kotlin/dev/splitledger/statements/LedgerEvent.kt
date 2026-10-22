package dev.splitledger.statements

import kotlinx.serialization.Serializable

/**
 * One event from the API's outbox (dev.splitledger.outbox.LedgerEvent plus [eventId]). Unknown
 * fields are ignored, so the API can add fields without breaking the worker.
 */
@Serializable
data class LedgerEvent(
    val eventId: Long,
    val type: EventType,
    val groupId: Long,
    val referenceId: Long,
    /** ISO date, e.g. "2026-03-05": expense date, or the day a payment was recorded. */
    val occurredOn: String,
    val description: String,
    val amountCents: Long,
    /** The ledger entries the event wrote; they sum to zero. */
    val postings: List<MemberPosting>,
)

@Serializable
enum class EventType { EXPENSE_RECORDED, EXPENSE_REVERSED, PAYMENT_RECORDED }

@Serializable
data class MemberPosting(val memberId: Long, val displayName: String? = null, val amountCents: Long)
