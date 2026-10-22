package dev.splitledger.statements

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import kotlinx.serialization.json.Json

/** Applies one event to its month's statement; separate from the Lambda types for testing. */
class StatementProcessor(private val store: StatementStore) {

    enum class Outcome { APPLIED, DUPLICATE }

    fun process(body: String): Outcome {
        val event = JSON.decodeFromString<LedgerEvent>(body)
        val current = store.load(event.groupId, MonthlyStatement.monthOf(event)) ?: MonthlyStatement.emptyFor(event)
        if (current.alreadyContains(event)) {
            return Outcome.DUPLICATE
        }
        store.save(current.apply(event))
        return Outcome.APPLIED
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Lambda entry point: dev.splitledger.statements.StatementHandler::handleRequest.
 *
 * Partial batch responses ("ReportBatchItemFailures"): only failed messages return to the queue.
 * On a FIFO queue, once one message fails, every later message of the batch is reported as failed
 * too, without processing it, because it may belong to the same group and must not overtake the
 * failed one. After three failed attempts SQS moves a message to the dead-letter queue (alarm).
 */
class StatementHandler(private val processor: StatementProcessor) : RequestHandler<SQSEvent, SQSBatchResponse> {

    /** Used by Lambda. Runs once per container, so the S3 client is created during the cold start. */
    constructor() : this(StatementProcessor(S3StatementStore.fromEnvironment()))

    override fun handleRequest(input: SQSEvent, context: Context): SQSBatchResponse {
        val failures = mutableListOf<SQSBatchResponse.BatchItemFailure>()
        for (message in input.records) {
            if (failures.isNotEmpty()) {
                failures += SQSBatchResponse.BatchItemFailure(message.messageId)
                continue
            }
            try {
                val outcome = processor.process(message.body)
                context.logger.log("message ${message.messageId}: $outcome\n")
            } catch (e: Exception) {
                context.logger.log("message ${message.messageId} failed: ${e.javaClass.simpleName}: ${e.message}\n")
                failures += SQSBatchResponse.BatchItemFailure(message.messageId)
            }
        }
        return SQSBatchResponse(failures)
    }
}
