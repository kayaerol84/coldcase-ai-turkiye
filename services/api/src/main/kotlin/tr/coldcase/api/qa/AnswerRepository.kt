package tr.coldcase.api.qa

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

data class NewAnswer(
    val id: UUID,
    val caseId: UUID,
    val askedBy: UUID,
    val question: String,
    val filters: Map<String, Any?>,
    val answer: ValidatedAnswer,
    val retrieval: Map<String, Any?>,
    val generator: Map<String, Any?>,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val latencyMs: Long,
)

/** Persistence for the Answer aggregate (answer + claims + citations) and citation reports. Every query is case-scoped. */
@Repository
class AnswerRepository(private val jdbc: JdbcClient, private val mapper: ObjectMapper) {

    @Transactional
    fun insert(a: NewAnswer) {
        val v = a.answer
        jdbc.sql(
            """
            INSERT INTO answers (id, case_id, asked_by, question, filters, outcome, abstain_reason, limitations, conflicts,
                                 suggested_lead, retrieval, generator, validation, input_tokens, output_tokens, latency_ms)
            VALUES (:id, :case, :by, :q, CAST(:filters AS jsonb), :outcome, :reason, CAST(:lim AS jsonb), CAST(:conf AS jsonb),
                    :lead, CAST(:retrieval AS jsonb), CAST(:generator AS jsonb), CAST(:validation AS jsonb), :in, :out, :latency)
            """.trimIndent()
        )
            .param("id", a.id).param("case", a.caseId).param("by", a.askedBy).param("q", a.question)
            .param("filters", mapper.writeValueAsString(a.filters))
            .param("outcome", v.outcome.name).param("reason", v.abstainReason?.name)
            .param("lim", mapper.writeValueAsString(v.limitations))
            .param("conf", mapper.writeValueAsString(v.conflicts))
            .param("lead", v.suggestedLead)
            .param("retrieval", mapper.writeValueAsString(a.retrieval))
            .param("generator", mapper.writeValueAsString(a.generator))
            .param("validation", mapper.writeValueAsString(v.report ?: emptyMap<String, Any?>()))
            .param("in", a.inputTokens?.toInt()).param("out", a.outputTokens?.toInt()).param("latency", a.latencyMs.toInt())
            .update()

        v.claims.forEachIndexed { i, c ->
            val claimId = UUID.randomUUID()
            jdbc.sql(
                "INSERT INTO answer_claims (id, case_id, answer_id, position, text, kind, speaker) VALUES (:id, :case, :a, :pos, :text, :kind, :speaker)"
            )
                .param("id", claimId).param("case", a.caseId).param("a", a.id).param("pos", i + 1)
                .param("text", c.text).param("kind", c.kind.name).param("speaker", c.speaker)
                .update()
            c.citations.forEach { cit ->
                val h = cit.passage.hit
                jdbc.sql(
                    """
                    INSERT INTO citations (id, case_id, target_type, target_id, text_segment_id, document_id, document_title, page_id,
                                           pdf_page_number, printed_page_label, source_kind, speaker, quoted_text, quote_start, quote_end)
                    VALUES (:id, :case, 'ANSWER_CLAIM', :target, :seg, :doc, :title, :page, :pdf, :label, :kind, :speaker, :quote, :qs, :qe)
                    """.trimIndent()
                )
                    .param("id", UUID.randomUUID()).param("case", a.caseId).param("target", claimId)
                    .param("seg", h.segmentId).param("doc", h.documentId).param("title", h.documentTitle).param("page", h.pageId)
                    .param("pdf", h.pdfPageNumber).param("label", h.printedPageLabel).param("kind", h.sourceKind)
                    .param("speaker", h.speaker).param("quote", cit.quote).param("qs", cit.quoteStart).param("qe", cit.quoteEnd)
                    .update()
            }
        }
    }

    fun caseOf(answerId: UUID): UUID? =
        jdbc.sql("SELECT case_id FROM answers WHERE id = :id").param("id", answerId)
            .query(UUID::class.java).optional().orElse(null)

    fun find(caseId: UUID, answerId: UUID): AnswerView? {
        val claims = claims(caseId, answerId)
        return jdbc.sql(
            """
            SELECT a.id, a.case_id, a.question, a.outcome, a.abstain_reason, a.review_status, a.limitations::text, a.conflicts::text,
                   a.suggested_lead, a.generator::text, a.validation::text, jsonb_array_length(a.retrieval->'passages'),
                   a.asked_by, u.display_name, a.created_at
              FROM answers a JOIN users u ON u.id = a.asked_by
             WHERE a.case_id = :case AND a.id = :id
            """.trimIndent()
        )
            .param("case", caseId).param("id", answerId)
            .query { rs, _ ->
                AnswerView(
                    rs.uuid(1), rs.uuid(2), rs.getString(3), AnswerOutcome.valueOf(rs.getString(4)),
                    rs.getString(5)?.let(AbstainReason::valueOf), ReviewStatus.valueOf(rs.getString(6)), claims,
                    mapper.readValue(rs.getString(7), Array<Limitation>::class.java).toList(),
                    mapper.readValue(rs.getString(8), Array<Conflict>::class.java).toList(),
                    rs.getString(9), json(rs.getString(10)), json(rs.getString(11)), rs.getInt(12),
                    AskedBy(rs.uuid(13), rs.getString(14)), rs.getObject(15, OffsetDateTime::class.java),
                )
            }
            .optional().orElse(null)
    }

    fun list(caseId: UUID, limit: Int): List<AnswerSummary> =
        jdbc.sql(
            """
            SELECT a.id, a.question, a.outcome, a.abstain_reason, a.review_status,
                   (SELECT count(*) FROM answer_claims c WHERE c.answer_id = a.id), a.asked_by, u.display_name, a.created_at
              FROM answers a JOIN users u ON u.id = a.asked_by
             WHERE a.case_id = :case
             ORDER BY a.created_at DESC, a.id LIMIT :limit
            """.trimIndent()
        )
            .param("case", caseId).param("limit", limit)
            .query { rs, _ ->
                AnswerSummary(
                    rs.uuid(1), rs.getString(2), AnswerOutcome.valueOf(rs.getString(3)), rs.getString(4)?.let(AbstainReason::valueOf),
                    ReviewStatus.valueOf(rs.getString(5)), rs.getInt(6), AskedBy(rs.uuid(7), rs.getString(8)),
                    rs.getObject(9, OffsetDateTime::class.java),
                )
            }
            .list()

    private fun claims(caseId: UUID, answerId: UUID): List<ClaimView> {
        val citations = jdbc.sql(
            """
            SELECT ct.target_id, ct.id, ct.text_segment_id, ct.document_id, ct.document_title, ct.page_id, ct.pdf_page_number,
                   ct.printed_page_label, ct.source_kind, ct.speaker, ct.quoted_text, ct.quote_start, ct.quote_end,
                   (SELECT count(*) FROM citation_reports r WHERE r.citation_id = ct.id)
              FROM citations ct JOIN answer_claims c ON c.id = ct.target_id AND ct.target_type = 'ANSWER_CLAIM'
             WHERE ct.case_id = :case AND c.answer_id = :a
             ORDER BY ct.pdf_page_number, ct.quote_start
            """.trimIndent()
        )
            .param("case", caseId).param("a", answerId)
            .query { rs, _ ->
                rs.uuid(1) to CitationView(
                    rs.uuid(2), rs.uuid(3), rs.uuid(4), rs.getString(5), rs.uuid(6), rs.getInt(7), rs.getString(8),
                    rs.getString(9), rs.getString(10), rs.getString(11), rs.getInt(12), rs.getInt(13), rs.getInt(14),
                )
            }
            .list().groupBy({ it.first }, { it.second })
        return jdbc.sql("SELECT id, position, text, kind, speaker FROM answer_claims WHERE case_id = :case AND answer_id = :a ORDER BY position")
            .param("case", caseId).param("a", answerId)
            .query { rs, _ ->
                val id = rs.uuid(1)
                ClaimView(id, rs.getInt(2), rs.getString(3), ClaimKind.valueOf(rs.getString(4)), rs.getString(5), citations[id].orEmpty())
            }
            .list()
    }

    /** True if [citationId] belongs to a claim of [answerId] in [caseId]. */
    fun citationBelongsTo(caseId: UUID, answerId: UUID, citationId: UUID): Boolean =
        jdbc.sql(
            """
            SELECT count(*) FROM citations ct JOIN answer_claims c ON c.id = ct.target_id AND ct.target_type = 'ANSWER_CLAIM'
             WHERE ct.id = :cit AND ct.case_id = :case AND c.answer_id = :a
            """.trimIndent()
        )
            .param("cit", citationId).param("case", caseId).param("a", answerId)
            .query { rs, _ -> rs.getInt(1) }.single() > 0

    fun insertReport(id: UUID, caseId: UUID, citationId: UUID, kind: CitationReportKind, note: String?, by: UUID) {
        jdbc.sql(
            "INSERT INTO citation_reports (id, case_id, citation_id, kind, note, created_by) VALUES (:id, :case, :cit, :kind, :note, :by)"
        )
            .param("id", id).param("case", caseId).param("cit", citationId).param("kind", kind.name).param("note", note).param("by", by)
            .update()
    }

    fun report(id: UUID): CitationReport =
        jdbc.sql(
            """
            SELECT r.id, r.citation_id, r.kind, r.note, r.created_by, u.display_name, r.created_at
              FROM citation_reports r JOIN users u ON u.id = r.created_by WHERE r.id = :id
            """.trimIndent()
        )
            .param("id", id)
            .query { rs, _ ->
                CitationReport(rs.uuid(1), rs.uuid(2), CitationReportKind.valueOf(rs.getString(3)), rs.getString(4),
                    AskedBy(rs.uuid(5), rs.getString(6)), rs.getObject(7, OffsetDateTime::class.java))
            }
            .single()

    @Suppress("UNCHECKED_CAST")
    private fun json(s: String): Map<String, Any?> = mapper.readValue(s, Map::class.java) as Map<String, Any?>

    private fun ResultSet.uuid(i: Int): UUID = getObject(i, UUID::class.java)
}
