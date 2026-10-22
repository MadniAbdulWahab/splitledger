package dev.splitledger.statements

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

const val ANNA = 1L
const val BEN = 2L

/** Anna pays 10.00 for herself and Ben: the ledger entries the API writes for that. */
fun expense(eventId: Long, cents: Long = 1000, date: String = "2026-03-05") = LedgerEvent(
    eventId = eventId, type = EventType.EXPENSE_RECORDED, groupId = 7, referenceId = 100 + eventId,
    occurredOn = date, description = "Groceries", amountCents = cents,
    // Odd cents go to the lower member id, as in the API's split rule, so the postings sum to zero.
    postings = listOf(
        MemberPosting(ANNA, "Anna", cents),
        MemberPosting(ANNA, "Anna", -(cents - cents / 2)),
        MemberPosting(BEN, "Ben", -(cents / 2)),
    ),
)

fun reversal(eventId: Long, of: LedgerEvent) = of.copy(
    eventId = eventId, type = EventType.EXPENSE_REVERSED,
    postings = of.postings.map { it.copy(amountCents = -it.amountCents) },
)

fun payment(eventId: Long, cents: Long, date: String = "2026-03-20") = LedgerEvent(
    eventId = eventId, type = EventType.PAYMENT_RECORDED, groupId = 7, referenceId = 500 + eventId,
    occurredOn = date, description = "Settle-up payment", amountCents = cents,
    postings = listOf(MemberPosting(BEN, "Ben", cents), MemberPosting(ANNA, "Anna", -cents)),
)

class MonthlyStatementTest {

    @Test
    fun `an expense credits the payer and debits every share`() {
        val statement = MonthlyStatement.emptyFor(expense(1)).apply(expense(1))

        assertEquals("2026-03", statement.month)
        assertEquals(1, statement.expenseCount)
        assertEquals(1000, statement.expensesCents)
        assertEquals(MemberLine(ANNA, "Anna", paidCents = 1000, shareCents = 500, netChangeCents = 500), statement.members[0])
        assertEquals(MemberLine(BEN, "Ben", shareCents = 500, netChangeCents = -500), statement.members[1])
        assertEquals(1, statement.lastEventId)
    }

    @Test
    fun `a reversal takes the expense back out`() {
        val groceries = expense(1)
        val statement = MonthlyStatement.emptyFor(groceries).apply(groceries).apply(reversal(2, groceries))

        assertEquals(1, statement.expenseCount)
        assertEquals(1, statement.reversedExpenseCount)
        assertEquals(0, statement.expensesCents)
        assertTrue(statement.members.all { it.paidCents == 0L && it.shareCents == 0L && it.netChangeCents == 0L })
    }

    @Test
    fun `a payment settles the balance`() {
        val statement = MonthlyStatement.emptyFor(expense(1)).apply(expense(1)).apply(payment(2, 500))

        assertEquals(1, statement.paymentCount)
        assertEquals(500, statement.paymentsCents)
        assertEquals(500, statement.members.first { it.memberId == ANNA }.paymentsReceivedCents)
        assertEquals(500, statement.members.first { it.memberId == BEN }.paymentsSentCents)
        assertTrue(statement.members.all { it.netChangeCents == 0L })
    }

    @Test
    fun `undoing a payment takes it back out`() {
        val payment = payment(2, 500)
        val undo = payment.copy(eventId = 3, type = EventType.PAYMENT_REVERSED,
            postings = payment.postings.map { it.copy(amountCents = -it.amountCents) })
        val statement = MonthlyStatement.emptyFor(expense(1)).apply(expense(1)).apply(payment).apply(undo)

        assertEquals(1, statement.paymentCount)
        assertEquals(1, statement.undonePaymentCount)
        assertEquals(0, statement.paymentsCents)
        assertEquals(1000, statement.expensesCents)
        assertTrue(statement.members.all { it.paymentsSentCents == 0L && it.paymentsReceivedCents == 0L })
        // Back to the state after the expense: Ben owes Anna 5.00 again.
        assertEquals(500, statement.members.first { it.memberId == ANNA }.netChangeCents)
    }

    @Test
    fun `net changes of all members always sum to zero`() {
        var statement = MonthlyStatement.emptyFor(expense(1))
        val events = listOf(expense(1, 999), expense(2, 4321), payment(3, 1234), expense(4, 17))
        events.forEach { statement = statement.apply(it) }
        statement = statement.apply(reversal(5, events[1]))

        assertEquals(0, statement.members.sumOf { it.netChangeCents })
    }

    @Test
    fun `repeated or foreign events are refused`() {
        val statement = MonthlyStatement.emptyFor(expense(5)).apply(expense(5))

        assertTrue(statement.alreadyContains(expense(5)))
        assertTrue(statement.alreadyContains(expense(4)))
        assertFalse(statement.alreadyContains(expense(6)))
        assertThrows<IllegalArgumentException> { statement.apply(expense(5)) }
        assertThrows<IllegalArgumentException> { statement.apply(expense(6, date = "2026-04-01")) }
    }
}
