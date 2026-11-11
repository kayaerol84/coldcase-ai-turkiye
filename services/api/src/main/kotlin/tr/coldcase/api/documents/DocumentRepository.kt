package tr.coldcase.api.documents

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

enum class DocumentStatus { PROCESSING, READY, READY_WITH_WARNINGS, FAILED }
enum class PageStatus { PENDING, EXTRACTED, NEEDS_OCR, OCR_DONE, FAILED }

data class DocumentRecord(
    val id: UUID,
    val caseId: UUID,
    val title: String,
    val sourceName: String?,
    val sourceDate: LocalDate?,
    val language: String,
    val status: DocumentStatus,
    val statusDetail: String?,
    val createdBy: UUID,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

data class VersionRecord(
    val id: UUID,
    val caseId: UUID,
    val documentId: UUID,
    val objectKey: String,
    val sha256: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val originalFilename: String?,
    val pageCount: Int?,
    val uploadedAt: OffsetDateTime,
)

data class PageCounts(val total: Int, val extracted: Int, val ocrDone: Int, val needsOcr: Int, val failed: Int, val pending: Int)

data class PageSummary(
    val id: UUID,
    val pdfPageNumber: Int,
    val printedPageLabel: String?,
    val printedLabelSource: String?,
    val status: PageStatus,
    val method: String?,
    val quality: Double?,
    val ocrConfidence: Double?,
    val error: String?,
)

data class PageDetail(
    val summary: PageSummary,
    val embeddedText: String?,
    val ocrText: String?,
    val ocrEngine: String?,
    val ocrEngineVersion: String?,
    val extractorVersion: String,
)

/** Extraction output for one page, written idempotently (re-runs overwrite the same row). */
data class PageExtraction(
    val pdfPageNumber: Int,
    val printedPageLabel: String?,
    val printedLabelSource: String?,
    val status: PageStatus,
    val embeddedText: String?,
    val quality: Double,
    val extractorVersion: String,
)

@Repository
class DocumentRepository(private val jdbc: JdbcClient, private val mapper: ObjectMapper) {

    private val docMapper = RowMapper { rs, _ ->
        DocumentRecord(
            id = rs.getObject("id", UUID::class.java),
            caseId = rs.getObject("case_id", UUID::class.java),
            title = rs.getString("title"),
            sourceName = rs.getString("source_name"),
            sourceDate = rs.getObject("source_date", LocalDate::class.java),
            language = rs.getString("language"),
            status = DocumentStatus.valueOf(rs.getString("status")),
            statusDetail = rs.getString("status_detail"),
            createdBy = rs.getObject("created_by", UUID::class.java),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
            updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
        )
    }

    private val versionMapper = RowMapper { rs, _ ->
        VersionRecord(
            id = rs.getObject("id", UUID::class.java),
            caseId = rs.getObject("case_id", UUID::class.java),
            documentId = rs.getObject("document_id", UUID::class.java),
            objectKey = rs.getString("object_key"),
            sha256 = rs.getString("sha256"),
            mimeType = rs.getString("mime_type"),
            fileSizeBytes = rs.getLong("file_size_bytes"),
            originalFilename = rs.getString("original_filename"),
            pageCount = rs.getObject("page_count") as Int?,
            uploadedAt = rs.getObject("uploaded_at", OffsetDateTime::class.java),
        )
    }

    private val pageSummaryMapper = RowMapper { rs, _ ->
        PageSummary(
            id = rs.getObject("id", UUID::class.java),
            pdfPageNumber = rs.getInt("pdf_page_number"),
            printedPageLabel = rs.getString("printed_page_label"),
            printedLabelSource = rs.getString("printed_label_source"),
            status = PageStatus.valueOf(rs.getString("extraction_status")),
            method = rs.getString("extraction_method"),
            quality = rs.getBigDecimal("extraction_quality")?.toDouble(),
            ocrConfidence = rs.getBigDecimal("ocr_confidence")?.toDouble(),
            error = rs.getString("error"),
        )
    }

    fun insertDocument(d: DocumentRecord) {
        jdbc.sql(
            """
            INSERT INTO documents (id, case_id, title, source_name, source_date, language, status, created_by)
            VALUES (:id, :case, :title, :sourceName, :sourceDate, :lang, :status, :by)
            """.trimIndent()
        )
            .param("id", d.id).param("case", d.caseId).param("title", d.title).param("sourceName", d.sourceName)
            .param("sourceDate", d.sourceDate).param("lang", d.language).param("status", d.status.name).param("by", d.createdBy)
            .update()
    }

    fun insertVersion(v: VersionRecord, uploadedBy: UUID, pdfMetadata: Map<String, Any?>) {
        jdbc.sql(
            """
            INSERT INTO document_versions
                (id, case_id, document_id, object_key, sha256, mime_type, file_size_bytes, original_filename, page_count, pdf_metadata, uploaded_by)
            VALUES (:id, :case, :doc, :key, :sha, :mime, :size, :fname, :pages, CAST(:meta AS jsonb), :by)
            """.trimIndent()
        )
            .param("id", v.id).param("case", v.caseId).param("doc", v.documentId).param("key", v.objectKey)
            .param("sha", v.sha256).param("mime", v.mimeType).param("size", v.fileSizeBytes)
            .param("fname", v.originalFilename).param("pages", v.pageCount)
            .param("meta", mapper.writeValueAsString(pdfMetadata)).param("by", uploadedBy)
            .update()
    }

    fun findDocument(id: UUID): DocumentRecord? =
        jdbc.sql("SELECT * FROM documents WHERE id = :id").param("id", id).query(docMapper).optional().orElse(null)

    fun listDocuments(caseId: UUID): List<DocumentRecord> =
        jdbc.sql("SELECT * FROM documents WHERE case_id = :c ORDER BY created_at DESC").param("c", caseId).query(docMapper).list()

    fun latestVersion(documentId: UUID): VersionRecord? =
        jdbc.sql("SELECT * FROM document_versions WHERE document_id = :d ORDER BY uploaded_at DESC LIMIT 1")
            .param("d", documentId).query(versionMapper).optional().orElse(null)

    fun findVersion(id: UUID): VersionRecord? =
        jdbc.sql("SELECT * FROM document_versions WHERE id = :id").param("id", id).query(versionMapper).optional().orElse(null)

    fun findVersionBySha(caseId: UUID, sha256: String): VersionRecord? =
        jdbc.sql("SELECT * FROM document_versions WHERE case_id = :c AND sha256 = :s")
            .param("c", caseId).param("s", sha256).query(versionMapper).optional().orElse(null)

    fun updateStatus(documentId: UUID, status: DocumentStatus, detail: String?) {
        jdbc.sql("UPDATE documents SET status = :s, status_detail = :d, updated_at = now() WHERE id = :id")
            .param("s", status.name).param("d", detail).param("id", documentId).update()
    }

    fun upsertExtractedPage(v: VersionRecord, p: PageExtraction) {
        jdbc.sql(
            """
            INSERT INTO pages (id, case_id, document_id, document_version_id, pdf_page_number, printed_page_label,
                               printed_label_source, extraction_status, extraction_method, extraction_quality,
                               embedded_text, extractor_version)
            VALUES (:id, :case, :doc, :v, :n, :label, :labelSource, :status, 'EMBEDDED_TEXT', :quality, :text, :ver)
            ON CONFLICT (document_version_id, pdf_page_number) DO UPDATE SET
                printed_page_label = EXCLUDED.printed_page_label,
                printed_label_source = EXCLUDED.printed_label_source,
                extraction_status = EXCLUDED.extraction_status,
                extraction_method = EXCLUDED.extraction_method,
                extraction_quality = EXCLUDED.extraction_quality,
                embedded_text = EXCLUDED.embedded_text,
                extractor_version = EXCLUDED.extractor_version,
                ocr_text = NULL, ocr_engine = NULL, ocr_engine_version = NULL, ocr_confidence = NULL,
                error = NULL, updated_at = now()
            """.trimIndent()
        )
            .param("id", UUID.randomUUID()).param("case", v.caseId).param("doc", v.documentId).param("v", v.id)
            .param("n", p.pdfPageNumber).param("label", p.printedPageLabel).param("labelSource", p.printedLabelSource)
            .param("status", p.status.name).param("quality", p.quality).param("text", p.embeddedText)
            .param("ver", p.extractorVersion)
            .update()
    }

    fun recordOcr(versionId: UUID, pageNumber: Int, text: String, confidence: Double, engine: String, engineVersion: String, detectedLabel: String?) {
        jdbc.sql(
            """
            UPDATE pages SET extraction_status = 'OCR_DONE', extraction_method = 'OCR',
                   ocr_text = :text, ocr_confidence = :conf, extraction_quality = :conf,
                   ocr_engine = :engine, ocr_engine_version = :ver, error = NULL, updated_at = now(),
                   printed_label_source = CASE WHEN printed_page_label IS NULL AND CAST(:label AS text) IS NOT NULL
                                               THEN 'OCR_HEADER_FOOTER' ELSE printed_label_source END,
                   printed_page_label = coalesce(printed_page_label, :label)
             WHERE document_version_id = :v AND pdf_page_number = :n
            """.trimIndent()
        )
            .param("text", text).param("conf", confidence).param("engine", engine).param("ver", engineVersion)
            .param("label", detectedLabel).param("v", versionId).param("n", pageNumber)
            .update()
    }

    fun markPageFailed(versionId: UUID, pageNumber: Int, error: String) {
        jdbc.sql("UPDATE pages SET extraction_status = 'FAILED', error = :e, updated_at = now() WHERE document_version_id = :v AND pdf_page_number = :n")
            .param("e", error.take(500)).param("v", versionId).param("n", pageNumber).update()
    }

    fun markPagesError(versionId: UUID, pageNumbers: List<Int>, error: String) {
        jdbc.sql("UPDATE pages SET error = :e, updated_at = now() WHERE document_version_id = :v AND pdf_page_number = ANY(:n)")
            .param("e", error.take(500)).param("v", versionId).param("n", pageNumbers.toTypedArray()).update()
    }

    fun pageCounts(versionId: UUID): PageCounts =
        jdbc.sql(
            """
            SELECT count(*) AS total,
                   count(*) FILTER (WHERE extraction_status = 'EXTRACTED') AS extracted,
                   count(*) FILTER (WHERE extraction_status = 'OCR_DONE') AS ocr_done,
                   count(*) FILTER (WHERE extraction_status = 'NEEDS_OCR') AS needs_ocr,
                   count(*) FILTER (WHERE extraction_status = 'FAILED') AS failed,
                   count(*) FILTER (WHERE extraction_status = 'PENDING') AS pending
              FROM pages WHERE document_version_id = :v
            """.trimIndent()
        )
            .param("v", versionId)
            .query { rs, _ ->
                PageCounts(rs.getInt("total"), rs.getInt("extracted"), rs.getInt("ocr_done"), rs.getInt("needs_ocr"), rs.getInt("failed"), rs.getInt("pending"))
            }
            .single()

    fun pageNumbersWithStatus(versionId: UUID, status: PageStatus): List<Int> =
        jdbc.sql("SELECT pdf_page_number FROM pages WHERE document_version_id = :v AND extraction_status = :s ORDER BY pdf_page_number")
            .param("v", versionId).param("s", status.name).query(Int::class.java).list()

    fun pages(versionId: UUID): List<PageSummary> =
        jdbc.sql("SELECT * FROM pages WHERE document_version_id = :v ORDER BY pdf_page_number")
            .param("v", versionId).query(pageSummaryMapper).list()

    fun page(versionId: UUID, pageNumber: Int): PageDetail? =
        jdbc.sql("SELECT * FROM pages WHERE document_version_id = :v AND pdf_page_number = :n")
            .param("v", versionId).param("n", pageNumber)
            .query { rs, n ->
                PageDetail(
                    summary = pageSummaryMapper.mapRow(rs, n)!!,
                    embeddedText = rs.getString("embedded_text"),
                    ocrText = rs.getString("ocr_text"),
                    ocrEngine = rs.getString("ocr_engine"),
                    ocrEngineVersion = rs.getString("ocr_engine_version"),
                    extractorVersion = rs.getString("extractor_version"),
                )
            }
            .optional().orElse(null)
}
