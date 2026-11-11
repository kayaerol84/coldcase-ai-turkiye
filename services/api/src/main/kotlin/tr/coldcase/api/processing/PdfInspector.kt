package tr.coldcase.api.processing

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import tr.coldcase.api.common.ApiException
import tr.coldcase.api.config.AppProperties
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

data class PdfInfo(val pageCount: Int, val metadata: Map<String, Any?>)

/** Validates uploads by content, not by filename (TR §5.1 step 5). */
@Component
class PdfInspector(private val props: AppProperties) {

    fun inspect(file: Path): PdfInfo {
        if (!hasPdfHeader(file)) {
            throw ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE", "Only PDF files are supported")
        }
        try {
            Loader.loadPDF(file.toFile()).use { doc ->
                val pages = doc.numberOfPages
                if (pages == 0) throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_PDF", "The PDF has no pages")
                if (pages > props.documents.maxPages) {
                    throw ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "TOO_MANY_PAGES",
                        "The PDF has $pages pages; the limit is ${props.documents.maxPages}",
                        mapOf("pageCount" to pages, "maxPages" to props.documents.maxPages),
                    )
                }
                val info = doc.documentInformation
                return PdfInfo(
                    pageCount = pages,
                    metadata = mapOf(
                        "pdfVersion" to doc.version.toString(),
                        "encrypted" to doc.isEncrypted, // owner-password only; content is readable
                        "title" to info?.title,
                        "author" to info?.author,
                        "producer" to info?.producer,
                        "creator" to info?.creator,
                        "creationDate" to info?.creationDate?.toInstant()?.toString(),
                    ).filterValues { it != null },
                )
            }
        } catch (e: InvalidPasswordException) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ENCRYPTED_PDF", "The PDF is password-protected")
        } catch (e: IOException) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CORRUPT_PDF", "The PDF could not be read")
        }
    }

    /** The PDF spec allows the %PDF- header anywhere in the first 1024 bytes. */
    private fun hasPdfHeader(file: Path): Boolean {
        val head = Files.newInputStream(file).use { it.readNBytes(1024) }
        val marker = "%PDF-".toByteArray()
        return (0..head.size - marker.size).any { i -> marker.indices.all { head[i + it] == marker[it] } }
    }
}
