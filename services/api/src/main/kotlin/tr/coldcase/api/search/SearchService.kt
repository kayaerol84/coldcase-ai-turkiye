package tr.coldcase.api.search

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import tr.coldcase.api.cases.CaseAccessService
import tr.coldcase.api.cases.Permission
import tr.coldcase.api.common.ApiException
import tr.coldcase.api.config.AppProperties
import tr.coldcase.api.embeddings.EmbeddingProvider
import tr.coldcase.api.embeddings.EmbeddingUnavailableException
import tr.coldcase.api.embeddings.toPgVector
import tr.coldcase.api.identity.AppUser
import java.util.UUID

enum class SearchMode { HYBRID, KEYWORD, SEMANTIC, EXACT }
enum class MatchKind { KEYWORD, SEMANTIC, EXACT_PHRASE }

data class SearchFilters(
    val documentIds: List<UUID>? = null,
    val pdfPageFrom: Int? = null,
    val pdfPageTo: Int? = null,
    val speaker: String? = null,
    /** EMBEDDED_TEXT or OCR */
    val sourceKind: String? = null,
)

data class Highlight(val start: Int, val end: Int)

/** Retrieval scores are for diagnostics only: they rank passages, they are not evidence of anything. */
data class Diagnostics(val lexicalRank: Int?, val lexicalScore: Double?, val vectorRank: Int?, val vectorSimilarity: Double?, val fusedScore: Double)

data class SearchHit(
    val segmentId: UUID,
    val documentId: UUID,
    val documentTitle: String,
    val pageId: UUID,
    val pdfPageNumber: Int,
    val printedPageLabel: String?,
    val speaker: String?,
    val speakerFromPreviousPage: Boolean,
    val text: String,
    val charStart: Int,
    val charEnd: Int,
    val sourceKind: String,
    val ocrConfidence: Double?,
    val highlights: List<Highlight>,
    val matchedBy: List<MatchKind>,
    val diagnostics: Diagnostics,
)

data class SearchResult(
    val mode: SearchMode,
    val modeUsed: SearchMode,
    val semanticAvailable: Boolean,
    val embeddingModel: String?,
    val results: List<SearchHit>,
    val tookMs: Long,
)

@Service
class SearchService(
    private val jdbc: JdbcClient,
    private val access: CaseAccessService,
    private val embeddings: EmbeddingProvider,
    private val props: AppProperties,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cfg get() = props.search

    companion object {
        private const val RRF_K = 60.0
        private const val PHRASE_BONUS = 0.05 // larger than any RRF score, so exact phrase matches rank first

        private val QUOTED = Regex("\"([^\"]{2,200})\"")
        private val WORD = Regex("[\\p{L}\\p{N}]+")
    }

    fun search(actor: AppUser, caseId: UUID, query: String, mode: SearchMode, filters: SearchFilters, limit: Int?): SearchResult {
        access.require(caseId, actor.id, Permission.CASE_VIEW)
        val q = query.trim()
        if (q.isEmpty()) throw ApiException(HttpStatus.BAD_REQUEST, "EMPTY_QUERY", "Query is empty")
        if (q.length > cfg.maxQueryLength) throw ApiException(HttpStatus.BAD_REQUEST, "QUERY_TOO_LONG", "Query is too long")
        val n = (limit ?: cfg.defaultLimit).coerceIn(1, cfg.maxLimit)
        val started = System.nanoTime()

        val phrases = QUOTED.findAll(q).map { it.groupValues[1] }.toList()
        val semanticAvailable = embeddings.modelId != null && embeddings.available()
        val modeUsed = when {
            mode == SearchMode.SEMANTIC && !semanticAvailable -> SearchMode.KEYWORD
            mode == SearchMode.HYBRID && !semanticAvailable -> SearchMode.KEYWORD
            else -> mode
        }

        val lexical: List<Pair<UUID, Double>> = when (modeUsed) {
            SearchMode.EXACT -> exact(caseId, q.removeSurrounding("\""), filters)
            SearchMode.KEYWORD -> if (mode == SearchMode.KEYWORD) keywordStrict(caseId, q, filters) else keywordLoose(caseId, q, filters)
            SearchMode.HYBRID -> keywordLoose(caseId, q, filters)
            SearchMode.SEMANTIC -> emptyList()
        }
        val vector: List<Pair<UUID, Double>> =
            if (modeUsed == SearchMode.HYBRID || modeUsed == SearchMode.SEMANTIC) {
                try {
                    semantic(caseId, q, filters)
                } catch (e: EmbeddingUnavailableException) {
                    log.warn("Semantic search unavailable for this request")
                    emptyList()
                }
            } else emptyList()

        val lexRank = lexical.mapIndexed { i, (id, score) -> id to (i + 1 to score) }.toMap()
        val vecRank = vector.mapIndexed { i, (id, sim) -> id to (i + 1 to sim) }.toMap()
        val ids = (lexRank.keys + vecRank.keys)
        val rows = hydrate(caseId, ids).associateBy { it.segmentId }

        val terms = TurkishText.queryTerms(QUOTED.replace(q, " "))
        val hits = ids.mapNotNull { id ->
            val row = rows[id] ?: return@mapNotNull null
            val l = lexRank[id]
            val v = vecRank[id]
            val folded = TurkishText.fold(row.text)
            val phraseHit = phrases.isNotEmpty() && phrases.all { folded.contains(TurkishText.fold(it)) }
            val fused = (l?.let { 1 / (RRF_K + it.first) } ?: 0.0) + (v?.let { 1 / (RRF_K + it.first) } ?: 0.0) +
                (if (phraseHit) PHRASE_BONUS else 0.0)
            val matched = buildList {
                if (l != null) add(if (modeUsed == SearchMode.EXACT) MatchKind.EXACT_PHRASE else MatchKind.KEYWORD)
                if (v != null) add(MatchKind.SEMANTIC)
                if (phraseHit && modeUsed != SearchMode.EXACT) add(MatchKind.EXACT_PHRASE)
            }
            val hl = TurkishText.highlights(row.text, if (modeUsed == SearchMode.EXACT) emptyList() else terms,
                if (modeUsed == SearchMode.EXACT) listOf(q.removeSurrounding("\"")) else phrases)
            row.copy(
                highlights = hl.map { Highlight(it.first, it.last + 1) },
                matchedBy = matched,
                diagnostics = Diagnostics(l?.first, l?.second, v?.first, v?.second, fused),
            )
        }.sortedByDescending { it.diagnostics.fusedScore }

        val results = dedupe(hits).take(n)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        meters.timer("coldcase.search", "mode", modeUsed.name).record(java.time.Duration.ofMillis(tookMs))
        if (results.isEmpty()) meters.counter("coldcase.search.empty", "mode", modeUsed.name).increment()
        return SearchResult(mode, modeUsed, semanticAvailable, if (semanticAvailable) embeddings.modelId else null, results, tookMs)
    }

    /** Drops lower-ranked chunks that mostly overlap a better one on the same page (chunk overlap). */
    private fun dedupe(hits: List<SearchHit>): List<SearchHit> {
        val kept = mutableListOf<SearchHit>()
        for (h in hits) {
            val dup = kept.any { k ->
                k.pageId == h.pageId && k.sourceKind == h.sourceKind &&
                    (minOf(k.charEnd, h.charEnd) - maxOf(k.charStart, h.charStart)).toDouble() /
                    minOf(k.charEnd - k.charStart, h.charEnd - h.charStart) > 0.5
            }
            if (!dup) kept += h
        }
        return kept
    }

    // --- Candidate queries. Every query is restricted to the case in SQL. ---

    private data class Sql(val where: String, val params: Map<String, Any?>)

    private fun filterSql(caseId: UUID, f: SearchFilters): Sql {
        val clauses = mutableListOf("s.case_id = :case")
        val params = mutableMapOf<String, Any?>("case" to caseId)
        if (!f.documentIds.isNullOrEmpty()) { clauses += "s.document_id = ANY(:docs)"; params["docs"] = f.documentIds.toTypedArray() }
        if (f.pdfPageFrom != null) { clauses += "s.pdf_page_number >= :pf"; params["pf"] = f.pdfPageFrom }
        if (f.pdfPageTo != null) { clauses += "s.pdf_page_number <= :pt"; params["pt"] = f.pdfPageTo }
        if (!f.speaker.isNullOrBlank()) {
            clauses += "coldcase_fold(s.speaker) LIKE :speaker"
            params["speaker"] = "%" + likeEscape(TurkishText.fold(f.speaker.trim())) + "%"
        }
        if (!f.sourceKind.isNullOrBlank()) { clauses += "s.source_kind = :kind"; params["kind"] = f.sourceKind }
        return Sql(clauses.joinToString(" AND "), params)
    }

    private fun run(sql: String, params: Map<String, Any?>): List<Pair<UUID, Double>> {
        var spec = jdbc.sql(sql)
        params.forEach { (k, v) -> spec = spec.param(k, v) }
        return spec.query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getDouble(2) }.list()
    }

    /** Keyword mode: web-search syntax ("phrase", -exclude), all terms required. */
    private fun keywordStrict(caseId: UUID, q: String, f: SearchFilters): List<Pair<UUID, Double>> {
        val w = filterSql(caseId, f)
        return run(
            """
            WITH q AS (SELECT websearch_to_tsquery('turkish', :q) || websearch_to_tsquery('simple', coldcase_fold(:q)) AS tsq)
            SELECT s.id, ts_rank_cd(s.search_tsv, q.tsq) AS score
              FROM text_segments s, q
             WHERE ${w.where} AND s.search_tsv @@ q.tsq
             ORDER BY score DESC, s.id LIMIT :k
            """.trimIndent(),
            w.params + mapOf("q" to q, "k" to cfg.candidatesPerMethod),
        )
    }

    /**
     * Hybrid's lexical side: any term may match (natural-language questions rarely contain every word
     * of the answer). Turkish stems OR'd with folded prefixes, so suffixes and missing diacritics still match.
     */
    private fun keywordLoose(caseId: UUID, q: String, f: SearchFilters): List<Pair<UUID, Double>> {
        val prefixes = WORD.findAll(TurkishText.fold(q)).map { it.value }
            .filter { it.length >= 3 && it !in TurkishText.STOPWORDS }
            .map { it.take(maxOf(4, it.length - 3)) + ":*" }
            .distinct().toList()
        val w = filterSql(caseId, f)
        val prefixQuery = prefixes.joinToString(" | ")
        return run(
            """
            WITH q AS (
                SELECT replace(plainto_tsquery('turkish', :q)::text, ' & ', ' | ')::tsquery
                       || (CASE WHEN :pq = '' THEN ''::tsquery ELSE to_tsquery('simple', :pq) END) AS tsq)
            SELECT s.id, ts_rank_cd(s.search_tsv, q.tsq) AS score
              FROM text_segments s, q
             WHERE ${w.where} AND s.search_tsv @@ q.tsq
             ORDER BY score DESC, s.id LIMIT :k
            """.trimIndent(),
            w.params + mapOf("q" to q, "pq" to prefixQuery, "k" to cfg.candidatesPerMethod),
        )
    }

    /** Exact phrase, insensitive to case and Turkish diacritics. */
    private fun exact(caseId: UUID, phrase: String, f: SearchFilters): List<Pair<UUID, Double>> {
        val w = filterSql(caseId, f)
        return run(
            """
            SELECT s.id, 1.0 FROM text_segments s
             WHERE ${w.where} AND coldcase_fold(s.text) LIKE :p
             ORDER BY s.document_id, s.segment_index LIMIT :k
            """.trimIndent(),
            w.params + mapOf("p" to "%" + likeEscape(TurkishText.fold(phrase)) + "%", "k" to cfg.candidatesPerMethod),
        )
    }

    /** Exact (brute-force) cosine search within the case: no approximate index, no cross-case candidates. */
    private fun semantic(caseId: UUID, q: String, f: SearchFilters): List<Pair<UUID, Double>> {
        val model = embeddings.modelId ?: return emptyList()
        val vec = embeddings.embedQuery(q).toPgVector()
        val w = filterSql(caseId, f)
        return run(
            """
            WITH q AS MATERIALIZED (SELECT CAST(:qv AS vector) AS v), -- parse the query vector once, not per row
                 ranked AS (
                     SELECT s.id, e.embedding <=> q.v AS dist
                       FROM segment_embeddings e JOIN text_segments s ON s.id = e.segment_id, q
                      WHERE ${w.where} AND e.case_id = :case AND e.model_id = :model)
            SELECT id, 1 - dist AS sim FROM ranked ORDER BY dist, id LIMIT :k
            """.trimIndent(),
            w.params + mapOf("qv" to vec, "model" to model, "k" to cfg.candidatesPerMethod),
        )
    }

    private fun hydrate(caseId: UUID, ids: Collection<UUID>): List<SearchHit> {
        if (ids.isEmpty()) return emptyList()
        return jdbc.sql(
            """
            SELECT s.id, s.document_id, d.title, s.page_id, s.pdf_page_number, p.printed_page_label, s.speaker,
                   coalesce((s.metadata->>'speakerFromPreviousPage')::boolean, false) AS carried,
                   s.text, s.char_start, s.char_end, s.source_kind, p.ocr_confidence
              FROM text_segments s
              JOIN documents d ON d.id = s.document_id
              JOIN pages p ON p.id = s.page_id
             WHERE s.case_id = :case AND s.id = ANY(:ids)
            """.trimIndent()
        )
            .param("case", caseId).param("ids", ids.toTypedArray())
            .query { rs, _ ->
                SearchHit(
                    rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3),
                    rs.getObject(4, UUID::class.java), rs.getInt(5), rs.getString(6), rs.getString(7), rs.getBoolean(8),
                    rs.getString(9), rs.getInt(10), rs.getInt(11), rs.getString(12), rs.getBigDecimal(13)?.toDouble(),
                    emptyList(), emptyList(), Diagnostics(null, null, null, null, 0.0),
                )
            }
            .list()
    }

    private fun likeEscape(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
