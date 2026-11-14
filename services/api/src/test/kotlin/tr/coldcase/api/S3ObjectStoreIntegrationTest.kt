package tr.coldcase.api

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.S3Exception
import tr.coldcase.api.config.AppProperties
import tr.coldcase.api.storage.ObjectStore
import tr.coldcase.api.storage.StorageConfig
import java.net.URI
import java.nio.file.Files
import java.util.UUID

/**
 * Runs the real S3 adapter against an S3-compatible server (SeaweedFS in CI and in the compose `s3` profile).
 * Skipped unless S3_TEST_ENDPOINT is set, e.g. S3_TEST_ENDPOINT=http://localhost:9000.
 */
@EnabledIfEnvironmentVariable(named = "S3_TEST_ENDPOINT", matches = ".+")
class S3ObjectStoreIntegrationTest {
    private val endpoint = System.getenv("S3_TEST_ENDPOINT")
    private val bucket = "coldcase-it-" + UUID.randomUUID().toString().take(8)

    private val store: ObjectStore = StorageConfig().objectStore(
        AppProperties(
            storage = AppProperties.Storage(
                type = "s3",
                s3 = AppProperties.S3(
                    endpoint = endpoint, bucket = bucket, accessKey = "coldcase", secretKey = "coldcase-dev-secret",
                    pathStyle = true, createBucket = true, serverSideEncryption = false,
                ),
            ),
        ),
    )

    private fun file(content: String) = Files.createTempFile("s3it-", ".bin").also { Files.writeString(it, content) }

    @Test
    fun `stores, finds and returns objects byte for byte`() {
        val key = "cases/${UUID.randomUUID()}/originals/abc.pdf"
        assertThat(store.exists(key)).isFalse()
        assertThat(store.putIfAbsent(key, file("orijinal içerik"), "application/pdf")).isTrue()
        assertThat(store.exists(key)).isTrue()
        assertThat(store.open(key).use { String(it.readAllBytes()) }).isEqualTo("orijinal içerik")
        val target = Files.createTempFile("s3it-dl-", ".bin")
        store.download(key, target)
        assertThat(Files.readString(target)).isEqualTo("orijinal içerik")
    }

    @Test
    fun `an existing original is never overwritten`() {
        val key = "cases/${UUID.randomUUID()}/originals/def.pdf"
        assertThat(store.putIfAbsent(key, file("first"), "application/pdf")).isTrue()
        assertThat(store.putIfAbsent(key, file("second"), "application/pdf")).isFalse()
        assertThat(store.open(key).use { String(it.readAllBytes()) }).isEqualTo("first")
    }

    @Test
    fun `the server enforces conditional writes the adapter relies on`() {
        // S3ObjectStore checks existence first and also sends If-None-Match: * to close the race between
        // two concurrent uploads. The server must reject the second write rather than silently replace it.
        val client = S3Client.builder()
            .endpointOverride(URI.create(endpoint)).region(Region.EU_CENTRAL_1).forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("coldcase", "coldcase-dev-secret")))
            .build()
        val key = "cases/${UUID.randomUUID()}/originals/race.pdf"
        store.putIfAbsent(key, file("winner"), "application/pdf")
        assertThatThrownBy {
            client.putObject({ it.bucket(bucket).key(key).ifNoneMatch("*") }, RequestBody.fromString("loser"))
        }.isInstanceOfSatisfying(S3Exception::class.java) { assertThat(it.statusCode()).isEqualTo(412) }
        assertThat(store.open(key).use { String(it.readAllBytes()) }).isEqualTo("winner")
    }
}
