package tr.coldcase.api.qa

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper
import tr.coldcase.api.audit.AuditLog
import tr.coldcase.api.cases.CaseAccessService
import tr.coldcase.api.cases.Permission
import tr.coldcase.api.common.ApiException
import tr.coldcase.api.common.NotFoundException
import tr.coldcase.api.config.AppProperties
import tr.coldcase.api.identity.AppUser
import tr.coldcase.api.search.SearchFilters
import tr.coldcase.api.search.SearchMode
import tr.coldcase.api.search.SearchService
import java.time.Duration
import java.util.UUID

/**
 * Source-grounded Q&A (T3, TR §6.3): retrieve passages in the case, let the model draft an answer from them
 * only, keep only claims whose citations verify against the retrieved text, and store the result as an
 * AI draft with everything needed to reproduce it.
 *
 * AI answers are research drafts: viewing and asking need RESEARCH_WRITE (Viewers see reviewed work only, T4).
 */
@Service
class QaService(
    private val access: CaseAccessService,
    private val search: SearchService,
    private val generator: AnswerGenerator,
    private val answers: AnswerRepository,
    private val audit: AuditLog,
    private val props: AppProperties,
    private val mapper: ObjectMapper,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cfg get() = props.qa
    private val validator get() = CitationValidator(cfg.minQuoteChars, cfg.maxClaims)

    fun available() = generator.available()

    fun ask(actor: AppUser, caseId: UUID, question: String, filters: SearchFilters): AnswerView {
        access.require(caseId, actor.id, Permission.RESEARCH_WRITE)
        val q = question.trim()
        if (q.isEmpty()) throw ApiException(HttpStatus.BAD_REQUEST, "EMPTY_QUESTION", "Question is empty")
        if (q.length > cfg.maxQuestionLength) throw ApiException(HttpStatus.BAD_REQUEST, "QUESTION_TOO_LONG", "Question is too long")
        if (!generator.available()) {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "QA_UNAVAILABLE", "Question answering is not enabled")
        }
        val started = System.nanoTime()

        val retrieved = search.search(actor, caseId, q, SearchMode.HYBRID, filters, cfg.passages)
        val passages = retrieved.results.mapIndexed { i, hit -> Passage("P${i + 1}", hit) }
        val retrieval = mapOf(
            "mode" to SearchMode.HYBRID.name,
            "modeUsed" to retrieved.modeUsed.name,
            "embeddingModel" to retrieved.embeddingModel,
            "limit" to cfg.passages,
            "passages" to passages.map {
                mapOf("id" to it.id, "segmentId" to it.hit.segmentId, "documentId" to it.hit.documentId,
                    "pdfPageNumber" to it.hit.pdfPageNumber, "fusedScore" to it.hit.diagnostics.fusedScore)
            },
        )
        val generatorInfo = mutableMapOf<String, Any?>("provider" to generator.provider, "promptVersion" to QaPrompt.VERSION)
        generatorInfo.putAll(generator.settings)

        var inputTokens: Long? = null
        var outputTokens: Long? = null
        val validated: ValidatedAnswer = if (passages.isEmpty()) {
            generatorInfo["called"] = false
            abstain(AbstainReason.NO_PASSAGES_FOUND)
        } else {
            val request = GenerationRequest(QaPrompt.SYSTEM, QaPrompt.user(q, passages, mapper), QaPrompt.SCHEMA)
            when (val result = generator.generate(request)) {
                is GenerationResult.Completed -> {
                    generatorInfo["model"] = result.model
                    inputTokens = result.inputTokens
                    outputTokens = result.outputTokens
                    val draft = parse(result.outputJson) ?: return failed("INVALID_OUTPUT")
                    validator.validate(draft, passages)
                }
                is GenerationResult.Declined -> {
                    generatorInfo["model"] = result.model
                    generatorInfo["refusalCategory"] = result.category
                    abstain(AbstainReason.MODEL_DECLINED)
                }
                is GenerationResult.Failed -> return failed(result.reason)
            }
        }

        val latencyMs = (System.nanoTime() - started) / 1_000_000
        val id = UUID.randomUUID()
        answers.insert(
            NewAnswer(id, caseId, actor.id, q, filtersJson(filters), validated, retrieval, generatorInfo, inputTokens, outputTokens, latencyMs)
        )
        val report = validated.report
        // Identifiers and counts only: the question and the answer text are case content.
        audit.record(
            actor.id, "ANSWER_CREATED", caseId, "answer", id.toString(),
            mapOf(
                "outcome" to validated.outcome.name, "abstainReason" to validated.abstainReason?.name,
                "provider" to generator.provider, "model" to generatorInfo["model"], "promptVersion" to QaPrompt.VERSION,
                "passages" to passages.size, "claims" to validated.claims.size,
                "citationsDropped" to report?.let { it.citationsProposed - it.citationsKept },
            ),
        )
        meters.timer("coldcase.qa", "outcome", validated.outcome.name, "reason", validated.abstainReason?.name ?: "none")
            .record(Duration.ofMillis(latencyMs))
        report?.let {
            meters.counter("coldcase.qa.citations.dropped").increment((it.citationsProposed - it.citationsKept).toDouble())
            meters.counter("coldcase.qa.claims.dropped").increment(it.claimsDroppedNoValidCitation.toDouble())
        }
        log.info("Answer {} in case {}: outcome={} claims={} passages={} latencyMs={}",
            id, caseId, validated.outcome, validated.claims.size, passages.size, latencyMs)
        return answers.find(caseId, id)!!
    }

    fun get(actor: AppUser, answerId: UUID): AnswerView {
        val caseId = answers.caseOf(answerId) ?: throw NotFoundException("Answer not found")
        access.require(caseId, actor.id, Permission.RESEARCH_WRITE)
        return answers.find(caseId, answerId) ?: throw NotFoundException("Answer not found")
    }

    fun list(actor: AppUser, caseId: UUID): List<AnswerSummary> {
        access.require(caseId, actor.id, Permission.RESEARCH_WRITE)
        return answers.list(caseId, 100)
    }

    fun reportCitation(actor: AppUser, answerId: UUID, citationId: UUID, kind: CitationReportKind, note: String?): CitationReport {
        val caseId = answers.caseOf(answerId) ?: throw NotFoundException("Answer not found")
        access.require(caseId, actor.id, Permission.RESEARCH_WRITE)
        if (!answers.citationBelongsTo(caseId, answerId, citationId)) throw NotFoundException("Citation not found")
        val id = UUID.randomUUID()
        answers.insertReport(id, caseId, citationId, kind, note?.trim()?.ifBlank { null }, actor.id)
        audit.record(actor.id, "CITATION_REPORTED", caseId, "citation", citationId.toString(), mapOf("kind" to kind.name, "answerId" to answerId))
        meters.counter("coldcase.qa.citation_reports", "kind", kind.name).increment()
        return answers.report(id)
    }

    private fun abstain(reason: AbstainReason) =
        ValidatedAnswer(AnswerOutcome.ABSTAINED, reason, emptyList(), emptyList(), emptyList(), null, null)

    private fun parse(json: String): AnswerDraft? = try {
        mapper.readerFor(AnswerDraft::class.java).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue<AnswerDraft>(json)
    } catch (e: Exception) {
        null
    }

    private fun failed(reason: String): Nothing {
        meters.counter("coldcase.qa.failures", "reason", reason).increment()
        log.warn("Answer generation failed: reason={}", reason)
        throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "QA_GENERATION_FAILED", "The answer could not be generated; try again later",
            mapOf("reason" to reason))
    }

    private fun filtersJson(f: SearchFilters): Map<String, Any?> = mapOf(
        "documentIds" to f.documentIds, "pdfPageFrom" to f.pdfPageFrom, "pdfPageTo" to f.pdfPageTo,
        "speaker" to f.speaker, "sourceKind" to f.sourceKind,
    ).filterValues { it != null }
}
