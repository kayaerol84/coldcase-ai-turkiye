package tr.coldcase.api.processing

import io.micrometer.core.instrument.MeterRegistry
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tr.coldcase.api.common.sha256
import tr.coldcase.api.config.AppProperties
import tr.coldcase.api.documents.DocumentRepository
import tr.coldcase.api.documents.DocumentStatus
import tr.coldcase.api.documents.PageExtraction
import tr.coldcase.api.documents.PageStatus
import tr.coldcase.api.documents.VersionRecord
import tr.coldcase.api.embeddings.EmbeddingProvider
import tr.coldcase.api.embeddings.EmbeddingUnavailableException
import tr.coldcase.api.indexing.Indexer
import tr.coldcase.api.indexing.SegmentRepository
import tr.coldcase.api.storage.ObjectStore
import java.io.IOException
import java.nio.file.Files

/** Errors that will not go away on retry; the job fails immediately. Messages start with a stable code. */
open class PermanentProcessingException(val code: String, message: String) : RuntimeException("$code: $message")

/**
 * Runs document-processing jobs. Never logs document text: only identifiers, counts and error codes.
 */
@Service
class DocumentProcessor(
    private val queue: JobQueue,
    private val documents: DocumentRepository,
    private val store: ObjectStore,
    private val ocr: OcrEngine,
    private val props: AppProperties,
    private val meters: MeterRegistry,
    private val indexer: Indexer,
    private val segments: SegmentRepository,
    private val embeddings: EmbeddingProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val workerId = "worker-" + ProcessHandle.current().pid()

    companion object {
        val EXTRACTOR_VERSION = "pdfbox-${org.apache.pdfbox.util.Version.getVersion()}/extract-v1"
    }

    /** Claims and runs one job. Returns false when nothing was runnable. */
    fun runNext(versionId: java.util.UUID? = null): Boolean {
        val job = queue.claim(workerId, props.worker.lease, versionId) ?: return false
        val started = System.nanoTime()
        var outcome = "succeeded"
        try {
            if (job.attempts > job.maxAttempts) {
                throw PermanentProcessingException("MAX_ATTEMPTS", "Job exceeded ${job.maxAttempts} attempts")
            }
            when (job.type) {
                JobType.EXTRACT_DOCUMENT -> extract(job)
                JobType.OCR_PAGES -> ocrPages(job)
                JobType.INDEX_DOCUMENT -> index(job)
            }
            queue.complete(job.id)
        } catch (e: PermanentProcessingException) {
            outcome = "failed"
            log.warn("Job {} ({}) failed permanently: {}", job.id, job.type, e.code)
            queue.fail(job, e.message ?: e.code, retryable = false)
        } catch (e: Exception) {
            outcome = "retried"
            log.warn("Job {} ({}) attempt {} failed: {}", job.id, job.type, job.attempts, e.javaClass.simpleName)
            queue.fail(job, "TRANSIENT: ${e.javaClass.simpleName}: ${e.message}", retryable = true)
        } finally {
            // Once extraction/OCR work for a version is finished, (re)build its search index.
            // Checked after this job's own status is written, so the last job to finish always sees none active.
            val extractionFailed = job.type == JobType.EXTRACT_DOCUMENT && outcome == "failed"
            if (job.type != JobType.INDEX_DOCUMENT && outcome != "retried" && !extractionFailed && !queue.hasActive(job.documentVersionId)) {
                queue.enqueueIndex(job.caseId, job.documentVersionId)
            }
            meters.timer("coldcase.jobs", "type", job.type.name, "outcome", outcome)
                .record(java.time.Duration.ofNanos(System.nanoTime() - started))
            refreshDocumentStatus(job.documentVersionId)
        }
        return true
    }

    /** Runs jobs until none are runnable (bounded). */
    fun drain(versionId: java.util.UUID? = null, max: Int = 1000): Int {
        var n = 0
        while (n < max && runNext(versionId)) n++
        return n
    }

    private fun extract(job: Job) {
        val version = documents.findVersion(job.documentVersionId)
            ?: throw PermanentProcessingException("VERSION_MISSING", "Document version not found")
        withVerifiedOriginal(version) { pdf ->
            val labels = pdf.documentCatalog.pageLabels?.labelsByPageIndices
            val stripper = PDFTextStripper()
            val needsOcr = mutableListOf<Int>()
            for (i in 0 until pdf.numberOfPages) {
                val pageNumber = i + 1
                stripper.startPage = pageNumber
                stripper.endPage = pageNumber
                val text = stripper.getText(pdf).replace("\r\n", "\n").trim()
                val quality = TextQuality.score(text)
                val enoughText = TextQuality.nonWhitespaceChars(text) >= props.extraction.minTextChars
                val status = if (enoughText && quality >= props.extraction.minQuality) PageStatus.EXTRACTED else PageStatus.NEEDS_OCR
                if (status == PageStatus.NEEDS_OCR) needsOcr += pageNumber

                // A number printed on the page beats the PDF's declared label; a wrong label is worse than none.
                val printed = if (status == PageStatus.EXTRACTED) PrintedLabelDetector.detect(text) else null
                val declared = labels?.getOrNull(i)?.takeIf { it.isNotBlank() }
                documents.upsertExtractedPage(
                    version,
                    PageExtraction(
                        pdfPageNumber = pageNumber,
                        printedPageLabel = printed ?: declared,
                        printedLabelSource = when {
                            printed != null -> "HEADER_FOOTER"
                            declared != null -> "PDF_PAGE_LABELS"
                            else -> null
                        },
                        status = status,
                        embeddedText = text.ifEmpty { null },
                        quality = quality,
                        extractorVersion = EXTRACTOR_VERSION,
                    ),
                )
            }
            meters.counter("coldcase.pages.extracted", "method", "embedded").increment((pdf.numberOfPages - needsOcr.size).toDouble())
            meters.counter("coldcase.pages.flagged_for_ocr").increment(needsOcr.size.toDouble())
            needsOcr.chunked(props.extraction.ocrBatchSize).forEach { batch ->
                queue.enqueue(version.caseId, version.id, JobType.OCR_PAGES, mapOf("pages" to batch))
            }
            log.info("Extracted version {}: {} pages, {} flagged for OCR", version.id, pdf.numberOfPages, needsOcr.size)
        }
    }

    private fun ocrPages(job: Job) {
        val version = documents.findVersion(job.documentVersionId)
            ?: throw PermanentProcessingException("VERSION_MISSING", "Document version not found")
        val pages = job.payload["pages"]?.map { it.asInt() }.orEmpty()
        if (!ocr.available) {
            documents.markPagesError(version.id, pages, "OCR_UNAVAILABLE")
            throw PermanentProcessingException("OCR_UNAVAILABLE", "OCR engine '${ocr.name}' is not installed")
        }
        withVerifiedOriginal(version) { pdf ->
            val renderer = PDFRenderer(pdf)
            for (pageNumber in pages) {
                if (pageNumber !in 1..pdf.numberOfPages) continue
                try {
                    val image = renderer.renderImageWithDPI(pageNumber - 1, props.extraction.ocr.dpi.toFloat(), ImageType.GRAY)
                    val result = ocr.recognize(image)
                    documents.recordOcr(
                        version.id, pageNumber, result.text.trim(), result.meanConfidence,
                        ocr.name, ocr.version ?: "unknown", PrintedLabelDetector.detect(result.text),
                    )
                    meters.counter("coldcase.pages.extracted", "method", "ocr").increment()
                } catch (e: OcrUnavailableException) {
                    throw PermanentProcessingException("OCR_UNAVAILABLE", e.message ?: "")
                } catch (e: Exception) {
                    // Page-level failure: keep going; the page can be reprocessed individually.
                    log.warn("OCR failed for version {} page {}: {}", version.id, pageNumber, e.javaClass.simpleName)
                    documents.markPageFailed(version.id, pageNumber, "OCR_FAILED: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun index(job: Job) {
        val version = documents.findVersion(job.documentVersionId)
            ?: throw PermanentProcessingException("VERSION_MISSING", "Document version not found")
        try {
            // On the final attempt, fall back to keyword-only indexing so the document is still searchable.
            indexer.index(version, allowWithoutEmbeddings = job.attempts >= job.maxAttempts)
        } catch (e: EmbeddingUnavailableException) {
            throw IllegalStateException("Embedding server unavailable") // transient: retried with backoff
        }
    }

    /** Downloads the original, verifies its SHA-256 against the recorded hash, and opens it. */
    private fun withVerifiedOriginal(version: VersionRecord, block: (PDDocument) -> Unit) {
        val tmp = Files.createTempFile("original-", ".pdf")
        try {
            store.download(version.objectKey, tmp)
            val actual = sha256(tmp)
            if (actual != version.sha256) {
                throw PermanentProcessingException("INTEGRITY_CHECK_FAILED", "Stored original does not match its recorded hash")
            }
            val pdf = try {
                Loader.loadPDF(tmp.toFile())
            } catch (e: IOException) {
                throw PermanentProcessingException("PDF_UNREADABLE", "The PDF could not be parsed")
            }
            pdf.use(block)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** Derives document status from outstanding jobs and page states. */
    fun refreshDocumentStatus(versionId: java.util.UUID) {
        val version = documents.findVersion(versionId) ?: return
        if (queue.hasActive(versionId)) {
            documents.updateStatus(version.documentId, DocumentStatus.PROCESSING, null)
            return
        }
        val counts = documents.pageCounts(versionId)
        val extractFailure = queue.latestFailure(versionId, JobType.EXTRACT_DOCUMENT)
        val indexFailure = queue.latestFailure(versionId, JobType.INDEX_DOCUMENT)
        val index = segments.indexInfo(versionId)
        val (status, detail) = when {
            extractFailure != null -> DocumentStatus.FAILED to extractFailure.substringBefore(':')
            counts.total == 0 -> DocumentStatus.FAILED to "NO_PAGES"
            indexFailure != null || index.status != "INDEXED" -> DocumentStatus.READY_WITH_WARNINGS to "NOT_SEARCHABLE"
            counts.needsOcr > 0 || counts.failed > 0 || counts.pending > 0 ->
                DocumentStatus.READY_WITH_WARNINGS to (queue.latestFailure(versionId, JobType.OCR_PAGES)?.substringBefore(':') ?: "PAGES_NEED_REVIEW")
            embeddings.modelId != null && index.embeddingModel == null -> DocumentStatus.READY_WITH_WARNINGS to "SEMANTIC_INDEX_MISSING"
            else -> DocumentStatus.READY to null
        }
        documents.updateStatus(version.documentId, status, detail)
    }
}
