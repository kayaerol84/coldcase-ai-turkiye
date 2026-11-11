package tr.coldcase.api.storage

import java.io.InputStream
import java.nio.file.Path

/**
 * Storage for original files and generated exports. Keys are content-addressed for originals,
 * and implementations must never overwrite an existing object (immutable originals, TR §3.1).
 */
interface ObjectStore {
    /** Stores [file] under [key] unless an object already exists there. Returns true if written. */
    fun putIfAbsent(key: String, file: Path, contentType: String): Boolean

    fun exists(key: String): Boolean

    fun open(key: String): InputStream

    /** Copies the object to [target] (overwriting target). */
    fun download(key: String, target: Path)
}

object ObjectKeys {
    fun original(caseId: java.util.UUID, sha256: String) = "cases/$caseId/originals/$sha256.pdf"
}
