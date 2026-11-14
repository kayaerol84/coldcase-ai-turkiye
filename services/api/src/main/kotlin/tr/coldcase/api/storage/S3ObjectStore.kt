package tr.coldcase.api.storage

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.ServerSideEncryption
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** S3-compatible store (SeaweedFS locally, an EU-region S3-compatible store in production). Uses conditional writes (If-None-Match) so originals are never replaced. */
class S3ObjectStore(
    private val s3: S3Client,
    private val bucket: String,
    private val serverSideEncryption: Boolean,
) : ObjectStore {

    override fun putIfAbsent(key: String, file: Path, contentType: String): Boolean {
        if (exists(key)) return false
        val req = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).ifNoneMatch("*")
        if (serverSideEncryption) req.serverSideEncryption(ServerSideEncryption.AES256)
        return try {
            s3.putObject(req.build(), RequestBody.fromFile(file))
            true
        } catch (e: S3Exception) {
            if (e.statusCode() == 412) false else throw e // precondition failed: someone else wrote it first
        }
    }

    override fun exists(key: String): Boolean = try {
        s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
        true
    } catch (e: NoSuchKeyException) {
        false
    } catch (e: S3Exception) {
        if (e.statusCode() == 404) false else throw e
    }

    override fun open(key: String): InputStream = s3.getObject { it.bucket(bucket).key(key) }

    override fun download(key: String, target: Path) {
        val tmp = Files.createTempFile(target.parent, ".dl-", ".tmp")
        s3.getObject({ it.bucket(bucket).key(key) }, ResponseTransformer.toFile(tmp.also { Files.delete(it) }))
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
