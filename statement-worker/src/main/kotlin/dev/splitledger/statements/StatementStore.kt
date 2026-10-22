package dev.splitledger.statements

import kotlinx.serialization.json.Json
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchKeyException

interface StatementStore {
    fun load(groupId: Long, month: String): MonthlyStatement?

    fun save(statement: MonthlyStatement)
}

/**
 * One JSON object per group and month: statements/group-<id>/<yyyy-MM>.json.
 *
 * Read-modify-write without locking is safe here because the queue is FIFO with the expense group
 * as message group: SQS hands one group's messages to only one invocation at a time.
 */
class S3StatementStore(private val s3: S3Client, private val bucket: String) : StatementStore {

    override fun load(groupId: Long, month: String): MonthlyStatement? =
        try {
            val bytes = s3.getObjectAsBytes { it.bucket(bucket).key(key(groupId, month)) }
            JSON.decodeFromString<MonthlyStatement>(bytes.asUtf8String())
        } catch (e: NoSuchKeyException) {
            null
        }

    override fun save(statement: MonthlyStatement) {
        s3.putObject(
            { it.bucket(bucket).key(key(statement.groupId, statement.month)).contentType("application/json") },
            RequestBody.fromString(JSON.encodeToString(statement)),
        )
    }

    companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

        fun key(groupId: Long, month: String) = "statements/group-$groupId/$month.json"

        /** Region and credentials come from the Lambda environment; the bucket name from Terraform. */
        fun fromEnvironment(): S3StatementStore {
            val bucket = System.getenv("STATEMENT_BUCKET") ?: error("STATEMENT_BUCKET is not set")
            val client = S3Client.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build()
            return S3StatementStore(client, bucket)
        }
    }
}
