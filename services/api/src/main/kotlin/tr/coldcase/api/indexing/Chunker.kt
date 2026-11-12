package tr.coldcase.api.indexing

import tr.coldcase.api.search.TurkishText

data class PageText(
    val pageId: java.util.UUID,
    val pdfPageNumber: Int,
    val text: String,
    /** EMBEDDED_TEXT or OCR — which page text the offsets refer to. */
    val sourceKind: String,
)

data class Chunk(
    val pageId: java.util.UUID,
    val pdfPageNumber: Int,
    val text: String,
    val charStart: Int,
    val charEnd: Int,
    val sourceKind: String,
    val speaker: String?,
    /** True when the speaker label was on an earlier page (the turn continues across a page break). */
    val speakerFromPreviousPage: Boolean,
    val tokenEstimate: Int,
)

/**
 * Splits page text into retrieval chunks (TR §5.3):
 * - never crosses a page boundary, so every chunk has exactly one page reference;
 * - starts a new chunk at every speaker change ("BAŞKAN —", "ILGAZ TUNA (Emniyet) —"), so turns by
 *   different people are never combined;
 * - within a turn, packs lines up to a target size, ending at a sentence boundary where possible,
 *   with a small overlap between consecutive chunks of the same turn.
 */
class Chunker(
    private val targetTokens: Int = 600,
    private val maxTokens: Int = 800,
    private val overlapTokens: Int = 60,
) {
    companion object {
        const val VERSION = "page-speaker-v1"

        private const val UPPER = "A-ZÇĞİÖŞÜÂÎÛ"
        /** Upper-case speaker label, optional parenthetical (constituency or title), then a dash or colon. */
        val SPEAKER = Regex("^\\s*([$UPPER][$UPPER.'’ ]{1,80}?)\\s*(\\([^)]{1,120}\\))?\\s*[—–:-]\\s+")
    }

    private val targetChars get() = (targetTokens * 3.5).toInt()
    private val maxChars get() = (maxTokens * 3.5).toInt()
    private val overlapChars get() = (overlapTokens * 3.5).toInt()

    fun chunk(pages: List<PageText>): List<Chunk> {
        val out = mutableListOf<Chunk>()
        var carriedSpeaker: String? = null
        for (page in pages) {
            if (page.text.isBlank()) continue
            val turns = splitTurns(page.text)
            for ((i, turn) in turns.withIndex()) {
                val fromPrevious = i == 0 && turn.speaker == null && carriedSpeaker != null
                val speaker = turn.speaker ?: if (i == 0) carriedSpeaker else null
                for ((s, e) in pack(page.text, turn.start, turn.end)) {
                    val raw = page.text.substring(s, e)
                    val trimmedStart = s + (raw.length - raw.trimStart().length)
                    val trimmedEnd = e - (raw.length - raw.trimEnd().length)
                    if (trimmedEnd <= trimmedStart) continue
                    val text = page.text.substring(trimmedStart, trimmedEnd)
                    // A bare printed page number or rule line carries nothing to search for.
                    if (text.none { it.isLetter() }) continue
                    out += Chunk(
                        page.pageId, page.pdfPageNumber, text, trimmedStart, trimmedEnd, page.sourceKind,
                        speaker, fromPrevious, TurkishText.estimateTokens(text),
                    )
                }
                if (turn.speaker != null) carriedSpeaker = turn.speaker
            }
        }
        return out
    }

    private data class Turn(val start: Int, val end: Int, val speaker: String?)

    private fun splitTurns(text: String): List<Turn> {
        val starts = mutableListOf<Pair<Int, String?>>(0 to null)
        var offset = 0
        for (line in text.split('\n')) {
            val m = SPEAKER.find(line)
            if (m != null && looksLikeName(m.groupValues[1])) {
                if (offset == 0) starts[0] = 0 to normalizeSpeaker(m.groupValues[1])
                else starts += offset to normalizeSpeaker(m.groupValues[1])
            }
            offset += line.length + 1
        }
        return starts.mapIndexed { i, (start, speaker) ->
            Turn(start, if (i + 1 < starts.size) starts[i + 1].first else text.length, speaker)
        }.filter { text.substring(it.start, it.end).isNotBlank() }
    }

    /** Rejects all-caps headings that are not names, e.g. a single short token like "A" or "TBMM". */
    private fun looksLikeName(s: String): Boolean {
        val t = s.trim()
        return t.length >= 4 && t.any { it.isLetter() }
    }

    private fun normalizeSpeaker(s: String) = s.trim().replace(Regex("\\s+"), " ")

    /** Packs [start, end) into windows of about targetChars, preferring sentence/line ends. */
    private fun pack(text: String, start: Int, end: Int): List<Pair<Int, Int>> {
        val windows = mutableListOf<Pair<Int, Int>>()
        var s = start
        while (s < end) {
            if (end - s <= maxChars) {
                windows += s to end
                break
            }
            var cut = findBreak(text, s, s + targetChars, minOf(end, s + maxChars))
            if (cut <= s) cut = minOf(end, s + maxChars)
            windows += s to cut
            val next = maxOf(cut - overlapChars, s + 1)
            // Start the overlap at a word boundary.
            s = text.indexOf(' ', next).let { if (it in next until cut) it + 1 else cut }
        }
        return windows
    }

    /** First sentence end in [from, limit), else a line break, else the last space after [windowStart]. */
    private fun findBreak(text: String, windowStart: Int, from: Int, limit: Int): Int {
        for (i in from until limit) {
            val c = text[i]
            if ((c == '.' || c == '?' || c == '!' || c == '…') && (i + 1 >= text.length || text[i + 1].isWhitespace())) return i + 1
        }
        val nl = text.indexOf('\n', from)
        if (nl in from until limit) return nl
        val sp = text.lastIndexOf(' ', limit - 1)
        return if (sp > windowStart) sp else limit
    }
}
