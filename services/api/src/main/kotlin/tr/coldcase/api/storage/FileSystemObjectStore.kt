package tr.coldcase.api.storage

import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Local-disk store for development and tests. Objects are written atomically and made read-only. */
class FileSystemObjectStore(root: Path) : ObjectStore {
    private val root: Path = root.toAbsolutePath().normalize().also { Files.createDirectories(it) }

    private fun resolve(key: String): Path {
        val p = root.resolve(key).normalize()
        require(p.startsWith(root)) { "Invalid object key" }
        return p
    }

    override fun putIfAbsent(key: String, file: Path, contentType: String): Boolean {
        val target = resolve(key)
        if (Files.exists(target)) return false
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, ".upload-", ".tmp")
        Files.copy(file, tmp, StandardCopyOption.REPLACE_EXISTING)
        return try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
            target.toFile().setReadOnly()
            true
        } catch (e: FileAlreadyExistsException) {
            Files.deleteIfExists(tmp)
            false
        }
    }

    override fun exists(key: String) = Files.exists(resolve(key))

    override fun open(key: String): InputStream = Files.newInputStream(resolve(key))

    override fun download(key: String, target: Path) {
        Files.copy(resolve(key), target, StandardCopyOption.REPLACE_EXISTING)
    }
}
