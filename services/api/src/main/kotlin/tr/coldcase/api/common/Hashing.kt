package tr.coldcase.api.common

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** SHA-256 of a file as lowercase hex, streamed (originals can be hundreds of MB). */
fun sha256(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}
