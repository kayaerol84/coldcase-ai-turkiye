package tr.coldcase.api.storage

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import tr.coldcase.api.config.AppProperties
import java.net.URI
import java.nio.file.Path

@Configuration
class StorageConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun objectStore(props: AppProperties): ObjectStore = when (props.storage.type) {
        "filesystem" -> FileSystemObjectStore(Path.of(props.storage.filesystemRoot))
        "s3" -> {
            val cfg = props.storage.s3
            val builder = S3Client.builder()
                .region(Region.of(cfg.region))
                .forcePathStyle(cfg.pathStyle)
                .credentialsProvider(
                    if (cfg.accessKey != null && cfg.secretKey != null) {
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(cfg.accessKey, cfg.secretKey))
                    } else {
                        DefaultCredentialsProvider.builder().build()
                    }
                )
            cfg.endpoint?.takeIf { it.isNotBlank() }?.let { builder.endpointOverride(URI.create(it)) }
            val client = builder.build()
            if (cfg.createBucket) {
                try {
                    client.headBucket { it.bucket(cfg.bucket) }
                } catch (e: NoSuchBucketException) {
                    log.info("Creating bucket {}", cfg.bucket)
                    client.createBucket { it.bucket(cfg.bucket) }
                }
            }
            S3ObjectStore(client, cfg.bucket, cfg.serverSideEncryption)
        }
        else -> error("Unknown app.storage.type '${props.storage.type}'")
    }
}
