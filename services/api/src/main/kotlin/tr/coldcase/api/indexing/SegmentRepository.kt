package tr.coldcase.api.indexing

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

data class IndexInfo(
    val status: String,
    val indexedAt: OffsetDateTime?,
    val chunkerVersion: String?,
    val embeddingModel: String?,
    val segmentCount: Int?,
)

data class NewSegment(val chunk: Chunk, val index: Int, val sha256: String, val embedding: String?)

@Repository
class SegmentRepository(private val jdbc: JdbcClient, private val mapper: ObjectMapper) {

    /** Page texts in reading order. OCR text is used only for pages whose extraction method is OCR. */
    fun pageTexts(versionId: UUID): List<PageText> =
        jdbc.sql(
            """
            SELECT id, pdf_page_number, extraction_method,
                   CASE WHEN extraction_method = 'OCR' THEN ocr_text ELSE embedded_text END AS text
              FROM pages
             WHERE document_version_id = :v AND extraction_status IN ('EXTRACTED', 'OCR_DONE')
             ORDER BY pdf_page_number
            """.trimIndent()
        )
            .param("v", versionId)
            .query { rs, _ ->
                PageText(
                    rs.getObject("id", UUID::class.java), rs.getInt("pdf_page_number"),
                    rs.getString("text") ?: "", rs.getString("extraction_method") ?: "EMBEDDED_TEXT",
                )
            }
            .list()

    /** Existing vectors for this version keyed by content hash, so unchanged text is not re-embedded. */
    fun existingEmbeddings(versionId: UUID, modelId: String): Map<String, String> =
        jdbc.sql(
            """
            SELECT e.content_sha256, e.embedding::text AS v
              FROM segment_embeddings e JOIN text_segments s ON s.id = e.segment_id
             WHERE s.document_version_id = :v AND e.model_id = :m
            """.trimIndent()
        )
            .param("v", versionId).param("m", modelId)
            .query { rs, _ -> rs.getString(1) to rs.getString(2) }
            .list().toMap()

    /** Replaces all segments of a version. Must run in a transaction so search never sees a partial index. */
    fun replace(
        caseId: UUID, documentId: UUID, versionId: UUID, segments: List<NewSegment>,
        modelId: String?, modelRevision: String?, dims: Int,
    ) {
        jdbc.sql("DELETE FROM text_segments WHERE document_version_id = :v").param("v", versionId).update()
        for (s in segments) {
            val id = UUID.randomUUID()
            val c = s.chunk
            jdbc.sql(
                """
                INSERT INTO text_segments (id, case_id, document_id, document_version_id, page_id, pdf_page_number,
                    segment_index, text, char_start, char_end, source_kind, speaker, content_sha256, token_estimate,
                    chunker_version, metadata)
                VALUES (:id, :case, :doc, :v, :page, :n, :idx, :text, :cs, :ce, :kind, :speaker, :sha, :tokens, :chunker,
                        CAST(:meta AS jsonb))
                """.trimIndent()
            )
                .param("id", id).param("case", caseId).param("doc", documentId).param("v", versionId)
                .param("page", c.pageId).param("n", c.pdfPageNumber).param("idx", s.index).param("text", c.text)
                .param("cs", c.charStart).param("ce", c.charEnd).param("kind", c.sourceKind).param("speaker", c.speaker)
                .param("sha", s.sha256).param("tokens", c.tokenEstimate).param("chunker", Chunker.VERSION)
                .param("meta", mapper.writeValueAsString(mapOf("speakerFromPreviousPage" to c.speakerFromPreviousPage)))
                .update()
            if (s.embedding != null && modelId != null) {
                jdbc.sql(
                    """
                    INSERT INTO segment_embeddings (segment_id, case_id, model_id, model_revision, dims, content_sha256, embedding)
                    VALUES (:id, :case, :m, :rev, :dims, :sha, CAST(:vec AS vector))
                    """.trimIndent()
                )
                    .param("id", id).param("case", caseId).param("m", modelId).param("rev", modelRevision)
                    .param("dims", dims).param("sha", s.sha256).param("vec", s.embedding)
                    .update()
            }
        }
    }

    fun markIndexed(versionId: UUID, status: String, embeddingModel: String?, count: Int?) {
        jdbc.sql(
            """
            UPDATE document_versions SET index_status = :s, indexed_at = now(), chunker_version = :c,
                   embedding_model = :m, segment_count = :n
             WHERE id = :v
            """.trimIndent()
        )
            .param("s", status).param("c", Chunker.VERSION).param("m", embeddingModel).param("n", count).param("v", versionId)
            .update()
    }

    fun analyze() {
        jdbc.sql("ANALYZE text_segments").update()
        jdbc.sql("ANALYZE segment_embeddings").update()
    }

    fun indexInfo(versionId: UUID): IndexInfo =
        jdbc.sql("SELECT index_status, indexed_at, chunker_version, embedding_model, segment_count FROM document_versions WHERE id = :v")
            .param("v", versionId)
            .query { rs, _ ->
                IndexInfo(
                    rs.getString(1), rs.getObject(2, OffsetDateTime::class.java), rs.getString(3), rs.getString(4),
                    rs.getObject(5) as Int?,
                )
            }
            .single()
}
