package tr.coldcase.api.qa

import tr.coldcase.api.search.TurkishText

/**
 * Enforces the citation invariants on a model draft (TR §7.3). Pure: no I/O, exhaustively unit-tested.
 *
 *  - A citation must name a passage that was actually retrieved for this question and quote it verbatim
 *    (only whitespace may differ). Otherwise it is dropped.
 *  - A claim without at least one valid citation is dropped.
 *  - Attribution comes from the source, not the model: a claim supported only by speaker passages is a
 *    SOURCE_STATEMENT by the cited speaker(s).
 *  - A conflict needs at least two distinct retrieved passages.
 *  - If nothing verifiable remains, the answer is an abstention.
 */
class CitationValidator(private val minQuoteChars: Int = 8, private val maxClaims: Int = 20) {

    fun validate(draft: AnswerDraft, passages: List<Passage>): ValidatedAnswer {
        val byId = passages.associateBy { it.id.uppercase() }
        var unknown = 0
        var notFound = 0
        var tooShort = 0
        var reattributed = 0
        var citationsProposed = 0
        var claimsDropped = 0

        val claims = draft.claims.take(maxClaims).mapNotNull { c ->
            citationsProposed += c.citations.size
            val cits = c.citations.mapNotNull { dc ->
                val passage = byId[dc.passageId.trim().uppercase()]
                when {
                    passage == null -> { unknown++; null }
                    dc.quote.count { !it.isWhitespace() } < minQuoteChars -> { tooShort++; null }
                    else -> locate(passage.hit.text, dc.quote)?.let { r ->
                        ValidatedCitation(passage, passage.hit.text.substring(r.first, r.last + 1),
                            passage.hit.charStart + r.first, passage.hit.charStart + r.last + 1)
                    } ?: run { notFound++; null }
                }
            }.distinctBy { Triple(it.passage.hit.segmentId, it.quoteStart, it.quoteEnd) }
            if (cits.isEmpty() || c.text.isBlank()) { claimsDropped++; return@mapNotNull null }

            val (kind, speaker) = attribute(c, cits)
            if (kind.name != c.kind || speaker != c.speaker?.trim()?.ifBlank { null }) reattributed++
            ValidatedClaim(c.text.trim(), kind, speaker, cits)
        }

        val conflicts = draft.conflicts.mapNotNull { dc ->
            val refs = dc.passageIds.mapNotNull { byId[it.trim().uppercase()] }.distinctBy { it.hit.segmentId }
            if (refs.size < 2 || dc.description.isBlank()) null
            else Conflict(dc.description.trim(), refs.map {
                PassageRef(it.hit.segmentId, it.hit.documentId, it.hit.documentTitle, it.hit.pdfPageNumber, it.hit.printedPageLabel)
            })
        }

        val report = ValidationReport(
            claimsProposed = draft.claims.size,
            claimsKept = claims.size,
            claimsDroppedNoValidCitation = claimsDropped + (draft.claims.size - draft.claims.take(maxClaims).size),
            citationsProposed = citationsProposed,
            citationsKept = claims.sumOf { it.citations.size },
            citationsDroppedUnknownPassage = unknown,
            citationsDroppedQuoteNotFound = notFound,
            citationsDroppedQuoteTooShort = tooShort,
            claimsReattributed = reattributed,
            conflictsProposed = draft.conflicts.size,
            conflictsKept = conflicts.size,
        )

        val limitations = buildList {
            draft.limitations.filter { it.isNotBlank() }.forEach { add(Limitation("MODEL", "MODEL_NOTE", it.trim())) }
            val removed = report.claimsDroppedNoValidCitation
            if (removed > 0) add(Limitation("SYSTEM", "CLAIMS_REMOVED_UNVERIFIABLE", count = removed))
            val cited = claims.flatMap { it.citations }.map { it.passage.hit }
            if (cited.any { it.sourceKind == "OCR" }) add(Limitation("SYSTEM", "CITES_OCR_TEXT"))
            if (cited.any { it.speakerFromPreviousPage }) add(Limitation("SYSTEM", "SPEAKER_INFERRED_FROM_PREVIOUS_PAGE"))
        }

        val modelAbstained = draft.outcome == "INSUFFICIENT_EVIDENCE"
        val (outcome, reason) = when {
            modelAbstained -> AnswerOutcome.ABSTAINED to AbstainReason.INSUFFICIENT_EVIDENCE
            claims.isEmpty() -> AnswerOutcome.ABSTAINED to AbstainReason.NO_VERIFIABLE_CLAIMS
            else -> AnswerOutcome.ANSWERED to null
        }
        return ValidatedAnswer(outcome, reason, claims, limitations, conflicts, draft.suggestedLead?.trim()?.ifBlank { null }, report)
    }

    /**
     * Speaker attribution is derived from the cited passages. If every citation comes from a passage with a
     * speaker, the claim is what those speakers said, whatever kind the model chose. The model's speaker name
     * is kept only when it names one of the cited speakers.
     */
    private fun attribute(c: DraftClaim, cits: List<ValidatedCitation>): Pair<ClaimKind, String?> {
        val citedSpeakers = cits.mapNotNull { it.passage.hit.speaker }.distinctBy { TurkishText.fold(it) }
        val allSpoken = cits.all { it.passage.hit.speaker != null }
        val modelKind = ClaimKind.entries.firstOrNull { it.name == c.kind } ?: ClaimKind.SOURCE_STATEMENT
        val modelSpeaker = c.speaker?.trim()?.ifBlank { null }
        val matching = modelSpeaker?.let { s -> citedSpeakers.firstOrNull { TurkishText.fold(it) == TurkishText.fold(s) } }
        return when {
            allSpoken -> ClaimKind.SOURCE_STATEMENT to (matching ?: citedSpeakers.joinToString(" / "))
            modelKind == ClaimKind.SOURCE_STATEMENT -> ClaimKind.SOURCE_STATEMENT to (matching ?: citedSpeakers.joinToString(" / ").ifBlank { modelSpeaker })
            else -> ClaimKind.DOCUMENTED_FACT to null
        }
    }

    companion object {
        private val WS = Regex("\\s+")

        /** Finds [quote] in [text] verbatim, allowing only whitespace runs (line breaks, spaces) to differ. */
        fun locate(text: String, quote: String): IntRange? {
            val tokens = quote.trim().split(WS).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            return Regex(tokens.joinToString("\\s+") { Regex.escape(it) }).find(text)?.range
        }
    }
}
