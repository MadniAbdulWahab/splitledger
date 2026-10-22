package dev.splitledger.statements

import com.amazonaws.services.lambda.runtime.ClientContext
import com.amazonaws.services.lambda.runtime.CognitoIdentity
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.LambdaLogger
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InMemoryStore : StatementStore {
    val saved = mutableMapOf<Pair<Long, String>, MonthlyStatement>()
    var saves = 0

    override fun load(groupId: Long, month: String) = saved[groupId to month]

    override fun save(statement: MonthlyStatement) {
        saved[statement.groupId to statement.month] = statement
        saves++
    }
}

class StatementHandlerTest {

    private val store = InMemoryStore()
    private val handler = StatementHandler(StatementProcessor(store))

    @Test
    fun `applies a batch and files events by month`() {
        val response = handler.handleRequest(batch(expense(1), expense(2, date = "2026-04-02"), payment(3, 500)), TestContext)

        assertEquals(emptyList<Any>(), response.batchItemFailures)
        assertEquals(setOf(7L to "2026-03", 7L to "2026-04"), store.saved.keys)
        // March got events 1 and 3 (the payment), April only event 2.
        assertEquals(3L, store.saved[7L to "2026-03"]!!.lastEventId)
        assertEquals(1, store.saved[7L to "2026-03"]!!.paymentCount)
        assertEquals(2L, store.saved[7L to "2026-04"]!!.lastEventId)
    }

    @Test
    fun `a repeated delivery changes nothing`() {
        handler.handleRequest(batch(expense(1)), TestContext)
        val before = store.saved.toMap()

        val response = handler.handleRequest(batch(expense(1)), TestContext)

        assertEquals(emptyList<Any>(), response.batchItemFailures)
        assertEquals(before, store.saved)
        assertEquals(1, store.saves)
    }

    @Test
    fun `after a failure the rest of the batch is retried, in order`() {
        val messages = listOf(
            message("m1", Json.encodeToString(expense(1))),
            message("m2", "{ not json"),
            message("m3", Json.encodeToString(expense(3))),
        )

        val response = handler.handleRequest(SQSEvent().apply { records = messages }, TestContext)

        assertEquals(listOf("m2", "m3"), response.batchItemFailures.map { it.itemIdentifier })
        assertEquals(1L, store.saved[7L to "2026-03"]!!.lastEventId)
    }

    @Test
    fun `nothing is saved for an empty batch`() {
        handler.handleRequest(SQSEvent().apply { records = emptyList() }, TestContext)

        assertNull(store.saved[7L to "2026-03"])
    }

    private fun batch(vararg events: LedgerEvent) =
        SQSEvent().apply { records = events.mapIndexed { i, e -> message("m$i", Json.encodeToString(e)) } }

    private fun message(id: String, body: String) = SQSEvent.SQSMessage().apply {
        messageId = id
        this.body = body
    }
}

/** The handler only uses the logger. */
object TestContext : Context {
    override fun getAwsRequestId() = "test"
    override fun getLogGroupName() = "test"
    override fun getLogStreamName() = "test"
    override fun getFunctionName() = "statement-worker"
    override fun getFunctionVersion() = "\$LATEST"
    override fun getInvokedFunctionArn() = "arn:aws:lambda:eu-central-1:000000000000:function:statement-worker"
    override fun getIdentity(): CognitoIdentity? = null
    override fun getClientContext(): ClientContext? = null
    override fun getRemainingTimeInMillis() = 30_000
    override fun getMemoryLimitInMB() = 512
    override fun getLogger(): LambdaLogger = object : LambdaLogger {
        override fun log(message: String) = print(message)
        override fun log(message: ByteArray) = print(String(message))
    }
}
