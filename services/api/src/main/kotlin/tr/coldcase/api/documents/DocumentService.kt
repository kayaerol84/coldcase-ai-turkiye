package tr.coldcase.api.documents

import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile
import tr.coldcase.api.audit.AuditLog
import tr.coldcase.api.cases.CaseAccessService
import tr.coldcase.api.cases.CaseRole
import tr.coldcase.api.cases.Permission
import tr.coldcase.api.common.ApiException
import tr.coldcase.api.common.NotFoundException
import tr.coldcase.api.config.AppProperties
import tr.coldcase.api.identity.AppUser
import tr.coldcase.api.processing.JobQueue
import tr.coldcase.api.processing.JobType
import tr.coldcase.api.processing.PdfInspector
import tr.coldcase.api.processing.sha256
import tr.coldcase.api.storage.ObjectKeys
import tr.coldcase.api.storage.ObjectStore
import java.io.InputStream
import java.nio.file.Files
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class UploadMetadata(val title: String?, val sourceName: String?, val sourceDate: LocalDate?, val language: String?)

data class DocumentView(val document: DocumentRecord, val version: VersionRecord?, val pages: PageCounts?)

@Service
class DocumentService(
    private val repo: DocumentRepository,
    private val access: CaseAccessService,
    private val inspector: PdfInspector,
    private val store: ObjectStore,
    private val queue: JobQueue,
    private val audit: AuditLog,
    private val tx: TransactionTemplate,
    private val props: AppProperties,
) {
    /**
     * Validates, hashes and stores the original, then queues processing. Returns immediately;
     * extraction happens asynchronously in the worker.
     */
    fun upload(actor: AppUser, caseId: UUID, file: MultipartFile, meta: UploadMetadata): DocumentView {
        access.require(caseId, actor.id, Permission.DOCUMENT_UPLOAD)
        if (file.isEmpty) throw ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "The uploaded file is empty")

        val tmp = Files.createTempFile("upload-", ".pdf")
        try {
            file.transferTo(tmp)
            val sha = sha256(tmp)
            val info = inspector.inspect(tmp)
            repo.findVersionBySha(caseId, sha)?.let { throw duplicate(it) }

            val key = ObjectKeys.original(caseId, sha)
            store.putIfAbsent(key, tmp, "application/pdf")

            val now = OffsetDateTime.now()
            val doc = DocumentRecord(
                id = UUID.randomUUID(), caseId = caseId,
                title = meta.title?.trim()?.takeIf { it.isNotEmpty() } ?: titleFrom(file.originalFilename),
                sourceName = meta.sourceName, sourceDate = meta.sourceDate, language = meta.language ?: "tr",
                status = DocumentStatus.PROCESSING, statusDetail = null, createdBy = actor.id, createdAt = now, updatedAt = now,
            )
            val version = VersionRecord(
                id = UUID.randomUUID(), caseId = caseId, documentId = doc.id, objectKey = key, sha256 = sha,
                mimeType = "application/pdf", fileSizeBytes = Files.size(tmp),
                originalFilename = file.originalFilename?.take(255), pageCount = info.pageCount, uploadedAt = now,
            )
            try {
                tx.executeWithoutResult {
                    repo.insertDocument(doc)
                    repo.insertVersion(version, actor.id, info.metadata)
                    queue.enqueue(caseId, version.id, JobType.EXTRACT_DOCUMENT)
                    audit.record(
                        actor.id, "DOCUMENT_UPLOADED", caseId, "document", doc.id.toString(),
                        mapOf("sha256" to sha, "sizeBytes" to version.fileSizeBytes, "pageCount" to info.pageCount),
                    )
                }
            } catch (e: DuplicateKeyException) {
                // Concurrent upload of the same file won the race.
                throw repo.findVersionBySha(caseId, sha)?.let { duplicate(it) } ?: e
            }
            return DocumentView(repo.findDocument(doc.id)!!, version, repo.pageCounts(version.id))
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    fun list(actor: AppUser, caseId: UUID): List<DocumentView> {
        access.require(caseId, actor.id, Permission.CASE_VIEW)
        return repo.listDocuments(caseId).map { d ->
            val v = repo.latestVersion(d.id)
            DocumentView(d, v, v?.let { repo.pageCounts(it.id) })
        }
    }

    fun get(actor: AppUser, documentId: UUID): DocumentView {
        val (doc, _) = authorize(actor, documentId, Permission.CASE_VIEW)
        val v = repo.latestVersion(doc.id)
        return DocumentView(doc, v, v?.let { repo.pageCounts(it.id) })
    }

    fun pages(actor: AppUser, documentId: UUID): List<PageSummary> {
        val (doc, _) = authorize(actor, documentId, Permission.CASE_VIEW)
        return repo.latestVersion(doc.id)?.let { repo.pages(it.id) }.orEmpty()
    }

    fun page(actor: AppUser, documentId: UUID, pageNumber: Int): PageDetail {
        val (doc, _) = authorize(actor, documentId, Permission.CASE_VIEW)
        val v = repo.latestVersion(doc.id) ?: throw NotFoundException("Page not found")
        return repo.page(v.id, pageNumber) ?: throw NotFoundException("Page not found")
    }

    /** Opens the immutable original for streaming. Every access is audited (source viewing, TR §12.3). */
    fun openOriginal(actor: AppUser, documentId: UUID, inline: Boolean): Pair<VersionRecord, InputStream> {
        val (doc, _) = authorize(actor, documentId, Permission.CASE_VIEW)
        val v = repo.latestVersion(doc.id) ?: throw NotFoundException("Document not found")
        audit.record(
            actor.id, if (inline) "DOCUMENT_VIEWED" else "DOCUMENT_DOWNLOADED", doc.caseId, "document", doc.id.toString(),
            mapOf("versionId" to v.id.toString(), "sha256" to v.sha256),
        )
        return v to store.open(v.objectKey)
    }

    /**
     * Re-runs processing. Without [pages], the whole document is re-extracted (OCR follows where needed).
     * With [pages], those pages are OCR'd again — used after a researcher reports an extraction problem.
     */
    fun reprocess(actor: AppUser, documentId: UUID, pages: List<Int>?): DocumentView {
        val (doc, _) = authorize(actor, documentId, Permission.DOCUMENT_UPLOAD)
        val v = repo.latestVersion(doc.id) ?: throw NotFoundException("Document not found")
        tx.executeWithoutResult {
            if (queue.hasActive(v.id)) {
                throw ApiException(HttpStatus.CONFLICT, "PROCESSING_IN_PROGRESS", "The document is already being processed")
            }
            if (pages.isNullOrEmpty()) {
                queue.enqueue(v.caseId, v.id, JobType.EXTRACT_DOCUMENT)
            } else {
                val count = repo.pageCounts(v.id).total
                if (count == 0) throw ApiException(HttpStatus.CONFLICT, "NOT_EXTRACTED", "Extract the document before reprocessing pages")
                val invalid = pages.filter { it !in 1..count }
                if (invalid.isNotEmpty()) {
                    throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGES", "Pages out of range", mapOf("invalidPages" to invalid))
                }
                pages.distinct().sorted().chunked(props.extraction.ocrBatchSize).forEach {
                    queue.enqueue(v.caseId, v.id, JobType.OCR_PAGES, mapOf("pages" to it))
                }
            }
            repo.updateStatus(doc.id, DocumentStatus.PROCESSING, null)
            audit.record(
                actor.id, "DOCUMENT_REPROCESS_REQUESTED", doc.caseId, "document", doc.id.toString(),
                mapOf("pages" to (pages?.distinct()?.sorted() ?: "ALL")),
            )
        }
        return get(actor, documentId)
    }

    private fun authorize(actor: AppUser, documentId: UUID, permission: Permission): Pair<DocumentRecord, CaseRole> {
        val doc = repo.findDocument(documentId) ?: throw NotFoundException("Document not found")
        // Non-members get the same 404 as for a missing document.
        val role = access.require(doc.caseId, actor.id, permission)
        return doc to role
    }

    private fun duplicate(existing: VersionRecord) = ApiException(
        HttpStatus.CONFLICT, "DUPLICATE_DOCUMENT", "This file is already in the case",
        mapOf("existingDocumentId" to existing.documentId.toString()),
    )

    private fun titleFrom(filename: String?): String =
        filename?.substringAfterLast('/')?.substringAfterLast('\\')?.removeSuffix(".pdf")?.removeSuffix(".PDF")
            ?.trim()?.take(500)?.ifEmpty { null } ?: "Adsız belge"
}
