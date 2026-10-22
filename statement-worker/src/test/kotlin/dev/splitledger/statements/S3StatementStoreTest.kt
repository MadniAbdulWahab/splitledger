package dev.splitledger.statements

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI

/**
 * Against Adobe's S3Mock in a container: a real S3 API, no AWS account. (LocalStack now needs an
 * account token, which a public CI should not depend on.)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3StatementStoreTest {

    private val s3mock = GenericContainer("adobe/s3mock:5.2.3")
        .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", BUCKET)
        .withExposedPorts(9090)
        .waitingFor(Wait.forListeningPort())

    private lateinit var s3: S3Client
    private lateinit var store: S3StatementStore

    @BeforeAll
    fun start() {
        s3mock.start()
        s3 = S3Client.builder()
            .endpointOverride(URI.create("http://${s3mock.host}:${s3mock.getMappedPort(9090)}"))
            .forcePathStyle(true)
            .region(Region.EU_CENTRAL_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
            .build()
        store = S3StatementStore(s3, BUCKET)
    }

    @AfterAll
    fun stop() {
        s3.close()
        s3mock.stop()
    }

    @Test
    fun `a missing month reads as null`() {
        assertNull(store.load(99, "2026-01"))
    }

    @Test
    fun `statements round-trip as JSON under a readable key`() {
        val statement = MonthlyStatement.emptyFor(expense(1)).apply(expense(1)).apply(payment(2, 500))

        store.save(statement)

        assertEquals(statement, store.load(7, "2026-03"))
        val json = s3.getObjectAsBytes { it.bucket(BUCKET).key("statements/group-7/2026-03.json") }.asUtf8String()
        assertTrue(json.contains("\"expensesCents\": 1000"), json)
    }

    @Test
    fun `processing through S3 applies each event once`() {
        val processor = StatementProcessor(store)

        processor.process(kotlinx.serialization.json.Json.encodeToString(expense(11, date = "2026-05-01")))
        val second = processor.process(kotlinx.serialization.json.Json.encodeToString(expense(11, date = "2026-05-01")))

        assertEquals(StatementProcessor.Outcome.DUPLICATE, second)
        assertEquals(1, store.load(7, "2026-05")!!.expenseCount)
    }

    private companion object {
        const val BUCKET = "statements-test"
    }
}
