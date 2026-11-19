package tr.coldcase.api.qa

import tr.coldcase.api.search.SearchHit
import java.time.OffsetDateTime
import java.util.UUID

enum class AnswerOutcome { ANSWERED, ABSTAINED }

enum class AbstainReason {
    /** Retrieval found nothing; the model was not called. */
    NO_PASSAGES_FOUND,
    /** The model reported that the passages do not answer the question. */
    INSUFFICIENT_EVIDENCE,
    /** The model answered, but no claim survived citation validation. */
    NO_VERIFIABLE_CLAIMS,
    /** The model declined (safety refusal). */
    MODEL_DECLINED,
}

/** AI output is a draft until a human reviews it (BR: never present AI output as established fact). */
enum class ReviewStatus { AI_DRAFT }

/**
 * SOURCE_STATEMENT: a person said it (testimony, speech) — always attributed to the speaker.
 * DOCUMENTED_FACT: the document itself records it (dates, procedure, contents of a document).
 */
enum class ClaimKind { SOURCE_STATEMENT, DOCUMENTED_FACT }

enum class CitationReportKind { DOES_NOT_SUPPORT_CLAIM, QUOTE_MISREPRESENTED, WRONG_PAGE, OTHER }

// --- What the model proposes (untrusted until validated) ---

data class DraftCitation(val passageId: String = "", val quote: String = "")
data class DraftClaim(val text: String = "", val kind: String = "", val speaker: String? = null, val citations: List<DraftCitation> = emptyList())
data class DraftConflict(val description: String = "", val passageIds: List<String> = emptyList())
data class AnswerDraft(
    val outcome: String = "",
    val claims: List<DraftClaim> = emptyList(),
    val limitations: List<String> = emptyList(),
    val conflicts: List<DraftConflict> = emptyList(),
    val suggestedLead: String? = null,
)

/** A retrieved segment as shown to the model, under a request-local id (P1, P2, …). */
data class Passage(val id: String, val hit: SearchHit)

// --- What survives validation ---

/** A quote located verbatim in a retrieved passage. Offsets are into the page's source text. */
data class ValidatedCitation(val passage: Passage, val quote: String, val quoteStart: Int, val quoteEnd: Int)

data class ValidatedClaim(val text: String, val kind: ClaimKind, val speaker: String?, val citations: List<ValidatedCitation>)

data class PassageRef(val segmentId: UUID, val documentId: UUID, val documentTitle: String, val pdfPageNumber: Int, val printedPageLabel: String?)

data class Conflict(val description: String, val passages: List<PassageRef>)

/** MODEL limitations are free text from the model; SYSTEM ones are codes the UI translates. */
data class Limitation(val source: String, val code: String, val text: String? = null, val count: Int? = null)

data class ValidationReport(
    val claimsProposed: Int,
    val claimsKept: Int,
    val claimsDroppedNoValidCitation: Int,
    val citationsProposed: Int,
    val citationsKept: Int,
    val citationsDroppedUnknownPassage: Int,
    val citationsDroppedQuoteNotFound: Int,
    val citationsDroppedQuoteTooShort: Int,
    val claimsReattributed: Int,
    val conflictsProposed: Int,
    val conflictsKept: Int,
)

data class ValidatedAnswer(
    val outcome: AnswerOutcome,
    val abstainReason: AbstainReason?,
    val claims: List<ValidatedClaim>,
    val limitations: List<Limitation>,
    val conflicts: List<Conflict>,
    val suggestedLead: String?,
    val report: ValidationReport?,
)

// --- Read model ---

data class CitationView(
    val id: UUID,
    val segmentId: UUID,
    val documentId: UUID,
    val documentTitle: String,
    val pageId: UUID,
    val pdfPageNumber: Int,
    val printedPageLabel: String?,
    val sourceKind: String,
    val speaker: String?,
    val quote: String,
    val quoteStart: Int,
    val quoteEnd: Int,
    val reportCount: Int,
)

data class ClaimView(val id: UUID, val position: Int, val text: String, val kind: ClaimKind, val speaker: String?, val citations: List<CitationView>)

data class AskedBy(val id: UUID, val displayName: String?)

data class AnswerView(
    val id: UUID,
    val caseId: UUID,
    val question: String,
    val outcome: AnswerOutcome,
    val abstainReason: AbstainReason?,
    val reviewStatus: ReviewStatus,
    val claims: List<ClaimView>,
    val limitations: List<Limitation>,
    val conflicts: List<Conflict>,
    val suggestedLead: String?,
    val generator: Map<String, Any?>,
    val validation: Map<String, Any?>,
    val retrievedPassages: Int,
    val askedBy: AskedBy,
    val createdAt: OffsetDateTime,
)

data class AnswerSummary(
    val id: UUID,
    val question: String,
    val outcome: AnswerOutcome,
    val abstainReason: AbstainReason?,
    val reviewStatus: ReviewStatus,
    val claimCount: Int,
    val askedBy: AskedBy,
    val createdAt: OffsetDateTime,
)

data class CitationReport(
    val id: UUID,
    val citationId: UUID,
    val kind: CitationReportKind,
    val note: String?,
    val createdBy: AskedBy,
    val createdAt: OffsetDateTime,
)
