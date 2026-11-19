package tr.coldcase.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tr.coldcase.api.qa.*
import tr.coldcase.api.search.Diagnostics
import tr.coldcase.api.search.SearchHit
import java.util.UUID

/** The citation invariants (docs/kb/domain/invariants.md, Q&A rows). Fictional people only. */
class CitationValidatorTest {
    private val validator = CitationValidator(minQuoteChars = 8)

    private fun hit(text: String, speaker: String? = null, charStart: Int = 100, sourceKind: String = "EMBEDDED_TEXT", carried: Boolean = false) =
        SearchHit(UUID.randomUUID(), UUID.randomUUID(), "Kurgusal Tutanak", UUID.randomUUID(), 7, "1426", speaker, carried, text,
            charStart, charStart + text.length, sourceKind, null, emptyList(), emptyList(), Diagnostics(null, null, null, null, 0.0))

    private val p1 = Passage("P1", hit("ILGAZ TUNA — Hatırladığım kadarıyla diskler\nevden alındı.", speaker = "ILGAZ TUNA"))
    private val p2 = Passage("P2", hit("NAZMİ ÖZTÜRK — Diskleri ben teslim etmedim, tutanak da görmedim.", speaker = "NAZMİ ÖZTÜRK"))
    private val p3 = Passage("P3", hit("Komisyon 12 Mart 1994 tarihinde toplanmıştır.", charStart = 0))
    private val passages = listOf(p1, p2, p3)

    private fun draft(vararg claims: DraftClaim, outcome: String = "ANSWERED", conflicts: List<DraftConflict> = emptyList()) =
        AnswerDraft(outcome, claims.toList(), emptyList(), conflicts, null)

    @Test
    fun `a verbatim quote is kept with page offsets, whitespace differences allowed`() {
        val r = validator.validate(draft(DraftClaim("Tuna diskleri evden alındığını söyledi.", "SOURCE_STATEMENT", "ILGAZ TUNA",
            listOf(DraftCitation("P1", "diskler evden alındı")))), passages)
        assertThat(r.outcome).isEqualTo(AnswerOutcome.ANSWERED)
        val c = r.claims.single().citations.single()
        assertThat(c.quote).isEqualTo("diskler\nevden alındı") // the source's own text, not the model's copy
        val idx = p1.hit.text.indexOf("diskler")
        assertThat(c.quoteStart).isEqualTo(100 + idx)
        assertThat(c.quoteEnd).isEqualTo(100 + idx + c.quote.length)
    }

    @Test
    fun `citations to passages that were not retrieved are dropped`() {
        val r = validator.validate(draft(DraftClaim("Uydurma iddia.", "DOCUMENTED_FACT", null,
            listOf(DraftCitation("P9", "diskler evden alındı")))), passages)
        assertThat(r.outcome).isEqualTo(AnswerOutcome.ABSTAINED)
        assertThat(r.abstainReason).isEqualTo(AbstainReason.NO_VERIFIABLE_CLAIMS)
        assertThat(r.report!!.citationsDroppedUnknownPassage).isEqualTo(1)
        assertThat(r.limitations.map { it.code }).contains("CLAIMS_REMOVED_UNVERIFIABLE")
    }

    @Test
    fun `paraphrased, corrected or stitched quotes are dropped`() {
        val r = validator.validate(draft(
            DraftClaim("a", "SOURCE_STATEMENT", null, listOf(DraftCitation("P1", "diskler evden alınmıştır"))),       // paraphrase
            DraftClaim("b", "SOURCE_STATEMENT", null, listOf(DraftCitation("P2", "Diskleri ben teslim etmedim ve görmedim"))), // stitched
            DraftClaim("c", "SOURCE_STATEMENT", null, listOf(DraftCitation("P2", "diskleri ben teslim etmedim"))),      // case changed
        ), passages)
        assertThat(r.claims).isEmpty()
        assertThat(r.report!!.citationsDroppedQuoteNotFound).isEqualTo(3)
        assertThat(r.report!!.claimsDroppedNoValidCitation).isEqualTo(3)
    }

    @Test
    fun `trivially short quotes do not count as support`() {
        val r = validator.validate(draft(DraftClaim("x", "DOCUMENTED_FACT", null, listOf(DraftCitation("P3", "1994")))), passages)
        assertThat(r.claims).isEmpty()
        assertThat(r.report!!.citationsDroppedQuoteTooShort).isEqualTo(1)
    }

    @Test
    fun `a claim keeps only its valid citations`() {
        val r = validator.validate(draft(DraftClaim("Toplantı tarihi.", "DOCUMENTED_FACT", null, listOf(
            DraftCitation("P3", "12 Mart 1994 tarihinde toplanmıştır"), DraftCitation("P3", "13 Mart 1994"), DraftCitation("p3", "12 Mart 1994 tarihinde toplanmıştır"),
        ))), passages)
        assertThat(r.claims.single().citations).hasSize(1)
        assertThat(r.report!!.citationsProposed).isEqualTo(3)
        assertThat(r.report!!.citationsKept).isEqualTo(1)
    }

    @Test
    fun `testimony is attributed to the cited speaker, whatever the model says`() {
        val r = validator.validate(draft(
            DraftClaim("Diskler evden alındı.", "DOCUMENTED_FACT", null, listOf(DraftCitation("P1", "Hatırladığım kadarıyla diskler evden alındı"))),
            DraftClaim("Öztürk diskleri teslim etmediğini söyledi.", "SOURCE_STATEMENT", "ILGAZ TUNA",
                listOf(DraftCitation("P2", "Diskleri ben teslim etmedim"))),
            DraftClaim("Öztürk diskleri teslim etmediğini söyledi.", "SOURCE_STATEMENT", "Nazmi Öztürk",
                listOf(DraftCitation("P2", "Diskleri ben teslim etmedim"))),
        ), passages)
        assertThat(r.claims.map { it.kind to it.speaker }).containsExactly(
            ClaimKind.SOURCE_STATEMENT to "ILGAZ TUNA",
            ClaimKind.SOURCE_STATEMENT to "NAZMİ ÖZTÜRK",
            ClaimKind.SOURCE_STATEMENT to "NAZMİ ÖZTÜRK",
        )
        assertThat(r.report!!.claimsReattributed).isEqualTo(3)
    }

    @Test
    fun `documented facts from non-speaker passages stay documented facts`() {
        val r = validator.validate(draft(DraftClaim("Komisyon 12 Mart 1994'te toplandı.", "DOCUMENTED_FACT", null,
            listOf(DraftCitation("P3", "Komisyon 12 Mart 1994 tarihinde")))), passages)
        assertThat(r.claims.single().kind).isEqualTo(ClaimKind.DOCUMENTED_FACT)
        assertThat(r.claims.single().speaker).isNull()
        assertThat(r.report!!.claimsReattributed).isZero()
    }

    @Test
    fun `conflicts need two distinct retrieved passages`() {
        val r = validator.validate(draft(
            DraftClaim("x", "SOURCE_STATEMENT", null, listOf(DraftCitation("P1", "diskler evden alındı"))),
            conflicts = listOf(
                DraftConflict("Disklerin teslimi konusunda farklı anlatımlar.", listOf("P1", "P2")),
                DraftConflict("Tek pasaj.", listOf("P1", "P1")),
                DraftConflict("Uydurma pasaj.", listOf("P1", "P7")),
            ),
        ), passages)
        assertThat(r.conflicts).hasSize(1)
        assertThat(r.conflicts.single().passages.map { it.segmentId }).containsExactly(p1.hit.segmentId, p2.hit.segmentId)
    }

    @Test
    fun `the model's own abstention is kept even with partial claims`() {
        val r = validator.validate(draft(DraftClaim("x", "DOCUMENTED_FACT", null, listOf(DraftCitation("P3", "12 Mart 1994 tarihinde"))),
            outcome = "INSUFFICIENT_EVIDENCE"), passages)
        assertThat(r.outcome).isEqualTo(AnswerOutcome.ABSTAINED)
        assertThat(r.abstainReason).isEqualTo(AbstainReason.INSUFFICIENT_EVIDENCE)
        assertThat(r.claims).hasSize(1)
    }

    @Test
    fun `OCR and inferred speakers are flagged as limitations`() {
        val ocr = Passage("P1", hit("ILGAZ TUNA — Diskler evden alındı, efendim.", speaker = "ILGAZ TUNA", sourceKind = "OCR", carried = true))
        val r = validator.validate(draft(DraftClaim("x", "SOURCE_STATEMENT", null, listOf(DraftCitation("P1", "Diskler evden alındı")))), listOf(ocr))
        assertThat(r.limitations.map { it.code }).contains("CITES_OCR_TEXT", "SPEAKER_INFERRED_FROM_PREVIOUS_PAGE")
    }

    @Test
    fun `quote matching is literal, not a regex`() {
        assertThat(CitationValidator.locate("fiyat (TL) 1.000.000 idi", "(TL) 1.000.000")).isNotNull()
        assertThat(CitationValidator.locate("fiyat TL 1x000x000 idi", "1.000.000")).isNull()
        assertThat(CitationValidator.locate("abc", "   ")).isNull()
    }
}
