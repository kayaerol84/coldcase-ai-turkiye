package tr.coldcase.api.storage

import org.springframework.stereotype.Component
import tr.coldcase.api.config.AppProperties
import tr.coldcase.api.common.sha256
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

class IntegrityException(message: String) : RuntimeException(message)

/**
 * Local copies of originals for page rendering, keyed by SHA-256 and verified against it when
 * fetched. Files are private to the process user and only ever hold content the caller is
 * already authorised to read (callers check case access first).
 */
@Component
class OriginalCache(private val store: ObjectStore, props: AppProperties) {
    private val dir: Path = Path.of(props.storage.cacheDir).toAbsolutePath().also {
        Files.createDirectories(it)
        it.toFile().setReadable(false, false); it.toFile().setReadable(true, true)
        it.toFile().setExecutable(false, false); it.toFile().setExecutable(true, true)
    }
    private val locks = ConcurrentHashMap<String, Any>()

    fun get(objectKey: String, sha256: String): Path {
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid hash" }
        val target = dir.resolve("$sha256.pdf")
        if (Files.exists(target)) return target
        synchronized(locks.computeIfAbsent(sha256) { Any() }) {
            if (Files.exists(target)) return target
            val tmp = Files.createTempFile(dir, ".fetch-", ".tmp")
            try {
                store.download(objectKey, tmp)
                if (sha256(tmp) != sha256) throw IntegrityException("Stored original does not match its recorded hash")
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(tmp)
            }
            return target
        }
    }
}
