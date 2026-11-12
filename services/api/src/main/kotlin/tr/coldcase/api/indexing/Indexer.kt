package tr.coldcase.api.indexing

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tr.coldcase.api.documents.VersionRecord
import tr.coldcase.api.embeddings.EmbeddingProvider
import tr.coldcase.api.embeddings.toPgVector
import java.security.MessageDigest
import java.util.HexFormat

/** Builds the search index for one document version: chunks, full-text vectors and embeddings. */
@Service
class Indexer(
    private val segments: SegmentRepository,
    private val embeddings: EmbeddingProvider,
    private val tx: TransactionTemplate,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val chunker = Chunker()

    /**
     * @param allowWithoutEmbeddings index for keyword search only when the embedding server is down
     *        (used on the last retry so documents still become searchable).
     * @return whether vectors were produced.
     */
    fun index(version: VersionRecord, allowWithoutEmbeddings: Boolean): Boolean {
        val chunks = chunker.chunk(segments.pageTexts(version.id))
        val hashes = chunks.map { sha256(it.text) }

        val modelId = embeddings.modelId
        val useEmbeddings = modelId != null && (embeddings.available() || !allowWithoutEmbeddings)
        val vectors: List<String?> = if (useEmbeddings) {
            val reuse = segments.existingEmbeddings(version.id, modelId!!)
            val missing = chunks.indices.filter { hashes[it] !in reuse }
            // Throws EmbeddingUnavailableException when the server is down: the job is retried.
            val fresh = embeddings.embedDocuments(missing.map { chunks[it].text })
            val byIndex = missing.zip(fresh).toMap()
            meters.counter("coldcase.segments.embedded").increment(missing.size.toDouble())
            meters.counter("coldcase.segments.embedding_reused").increment((chunks.size - missing.size).toDouble())
            chunks.indices.map { i -> reuse[hashes[i]] ?: byIndex.getValue(i).toPgVector() }
        } else {
            chunks.map { null }
        }

        tx.executeWithoutResult {
            segments.replace(
                version.caseId, version.documentId, version.id,
                chunks.mapIndexed { i, c -> NewSegment(c, i, hashes[i], vectors[i]) },
                if (useEmbeddings) modelId else null, embeddings.modelRevision, embeddings.dims,
            )
            segments.markIndexed(version.id, "INDEXED", if (useEmbeddings) modelId else null, chunks.size)
        }
        // Refresh planner statistics now rather than waiting for autovacuum: right after a large
        // insert, stale statistics make the first searches choose slow plans.
        segments.analyze()
        log.info("Indexed version {}: {} segments, embeddings={}", version.id, chunks.size, useEmbeddings)
        return useEmbeddings
    }

    private fun sha256(s: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
}
