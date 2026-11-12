package tr.coldcase.api.documents

import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import tr.coldcase.api.identity.UserService
import tr.coldcase.api.indexing.IndexInfo
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class FileInfo(
    val versionId: UUID,
    val sha256: String,
    val sizeBytes: Long,
    val mimeType: String,
    val originalFilename: String?,
    val pageCount: Int?,
    val uploadedAt: OffsetDateTime,
)

data class DocumentResponse(
    val id: UUID,
    val caseId: UUID,
    val title: String,
    val sourceName: String?,
    val sourceDate: LocalDate?,
    val language: String,
    val status: DocumentStatus,
    val statusDetail: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val file: FileInfo?,
    val pages: PageCounts?,
    /** Searchable once status is INDEXED. embeddingModel is null when only keyword search covers it. */
    val index: IndexInfo?,
) {
    companion object {
        fun of(v: DocumentView) = v.document.let { d ->
            DocumentResponse(
                d.id, d.caseId, d.title, d.sourceName, d.sourceDate, d.language, d.status, d.statusDetail, d.createdAt, d.updatedAt,
                v.version?.let { FileInfo(it.id, it.sha256, it.fileSizeBytes, it.mimeType, it.originalFilename, it.pageCount, it.uploadedAt) },
                v.pages,
                v.index,
            )
        }
    }
}

data class PageResponse(
    val id: UUID,
    val pdfPageNumber: Int,
    val printedPageLabel: String?,
    val printedLabelSource: String?,
    val status: PageStatus,
    val method: String?,
    val quality: Double?,
    val ocrConfidence: Double?,
    val error: String?,
    // Detail-only fields. Embedded and OCR text are kept separate and never merged (TR §5.2).
    val embeddedText: String? = null,
    val ocrText: String? = null,
    val ocrEngine: String? = null,
    val ocrEngineVersion: String? = null,
    val extractorVersion: String? = null,
) {
    companion object {
        fun of(p: PageSummary) = PageResponse(
            p.id, p.pdfPageNumber, p.printedPageLabel, p.printedLabelSource, p.status, p.method, p.quality, p.ocrConfidence, p.error,
        )

        fun of(p: PageDetail) = of(p.summary).copy(
            embeddedText = p.embeddedText, ocrText = p.ocrText, ocrEngine = p.ocrEngine,
            ocrEngineVersion = p.ocrEngineVersion, extractorVersion = p.extractorVersion,
        )
    }
}

data class ReprocessRequest(val pages: List<Int>? = null)

data class PageReportRequest(val kind: PageReportKind, @field:Size(max = 2000) val note: String? = null)

@RestController
@RequestMapping("/api/v1")
class DocumentController(private val documents: DocumentService, private val users: UserService) {

    @PostMapping("/cases/{caseId}/documents", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun upload(
        @PathVariable caseId: UUID,
        @RequestPart("file") file: MultipartFile,
        @RequestParam(required = false) title: String?,
        @RequestParam(required = false) sourceName: String?,
        @RequestParam(required = false) sourceDate: LocalDate?,
        @RequestParam(required = false) language: String?,
    ) = DocumentResponse.of(documents.upload(users.current(), caseId, file, UploadMetadata(title, sourceName, sourceDate, language)))

    @GetMapping("/cases/{caseId}/documents")
    fun list(@PathVariable caseId: UUID) = documents.list(users.current(), caseId).map(DocumentResponse::of)

    @GetMapping("/documents/{documentId}")
    fun get(@PathVariable documentId: UUID) = DocumentResponse.of(documents.get(users.current(), documentId))

    @GetMapping("/documents/{documentId}/pages")
    fun pages(@PathVariable documentId: UUID) = documents.pages(users.current(), documentId).map(PageResponse::of)

    @GetMapping("/documents/{documentId}/pages/{pageNumber}")
    fun page(@PathVariable documentId: UUID, @PathVariable pageNumber: Int) =
        PageResponse.of(documents.page(users.current(), documentId, pageNumber))

    @GetMapping("/documents/{documentId}/file")
    fun file(
        @PathVariable documentId: UUID,
        @RequestParam(defaultValue = "attachment") disposition: String,
    ): ResponseEntity<StreamingResponseBody> {
        val inline = disposition == "inline"
        val (version, stream) = documents.openOriginal(users.current(), documentId, inline)
        val cd = (if (inline) ContentDisposition.inline() else ContentDisposition.attachment())
            .filename(version.originalFilename ?: "${version.sha256}.pdf", StandardCharsets.UTF_8).build()
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .contentLength(version.fileSizeBytes)
            .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .header("X-Content-Type-Options", "nosniff")
            .header("X-Document-Sha256", version.sha256)
            .body(StreamingResponseBody { out -> stream.use { it.transferTo(out) } })
    }

    @GetMapping("/documents/{documentId}/pages/{pageNumber}/image", produces = [MediaType.IMAGE_PNG_VALUE])
    fun pageImage(
        @PathVariable documentId: UUID,
        @PathVariable pageNumber: Int,
        @RequestParam(defaultValue = "110") dpi: Int,
    ): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
            .header("X-Content-Type-Options", "nosniff")
            .body(documents.renderPage(users.current(), documentId, pageNumber, dpi))

    @PostMapping("/documents/{documentId}/pages/{pageNumber}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    fun reportPage(@PathVariable documentId: UUID, @PathVariable pageNumber: Int, @Valid @RequestBody req: PageReportRequest) =
        documents.reportPage(users.current(), documentId, pageNumber, req.kind, req.note)

    @GetMapping("/documents/{documentId}/reports")
    fun reports(@PathVariable documentId: UUID) = documents.reports(users.current(), documentId)

    @PostMapping("/documents/{documentId}/reprocess")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun reprocess(@PathVariable documentId: UUID, @RequestBody(required = false) req: ReprocessRequest?) =
        DocumentResponse.of(documents.reprocess(users.current(), documentId, req?.pages))
}
