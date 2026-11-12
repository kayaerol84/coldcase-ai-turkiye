package tr.coldcase.api.search

/** Kotlin twin of the SQL function coldcase_fold: one output char per input char, so offsets line up. */
object TurkishText {
    private const val FROM = "İIıÂâÎîÛûÇçĞğÖöŞşÜü"
    private const val TO = "iiiaaiiuuccggoossuu"

    fun fold(s: String): String {
        val out = CharArray(s.length)
        for (i in s.indices) {
            val c = s[i]
            val k = FROM.indexOf(c)
            out[i] = if (k >= 0) TO[k] else c.lowercaseChar()
        }
        return String(out)
    }

    /** Rough token count for multilingual subword tokenizers on Turkish (~3.5 chars/token). */
    fun estimateTokens(s: String): Int = (s.length / 3.5).toInt().coerceAtLeast(1)

    private val word = Regex("[\\p{L}\\p{N}]+")

    /** Folded Turkish function words: ignored for prefix matching and highlighting (they occur everywhere). */
    val STOPWORDS = setOf(
        "ve", "ile", "bir", "bu", "su", "o", "ne", "mi", "mu", "da", "de", "ki", "icin", "gibi", "daha", "cok",
        "hakkinda", "olan", "olarak", "ama", "veya", "ya", "hangi", "nasil", "neden", "niye", "kim", "kimin",
        "nerede", "zaman", "her", "hic", "sey", "ise", "en", "var", "yok", "midir", "nedir", "dair",
    )

    /** Folded word tokens of a query, for highlighting. */
    fun queryTerms(query: String): List<String> =
        word.findAll(fold(query)).map { it.value }.filter { it.length >= 2 && it !in STOPWORDS }.distinct().toList()

    /**
     * Character ranges in [text] to highlight: words whose folded form starts with a query term's
     * prefix (handles Turkish suffixes, e.g. "kayıt" → "kayıtlarının"), plus exact phrase occurrences.
     */
    fun highlights(text: String, terms: List<String>, phrases: List<String> = emptyList()): List<IntRange> {
        val folded = fold(text)
        val ranges = mutableListOf<IntRange>()
        for (m in word.findAll(folded)) {
            val w = m.value
            if (terms.any { t -> w.startsWith(t.take(maxOf(4, t.length - 3))) && w.length >= minOf(t.length, 4) }) {
                ranges += m.range
            }
        }
        for (p in phrases.map { fold(it).trim() }.filter { it.length >= 2 }) {
            var i = folded.indexOf(p)
            while (i >= 0) {
                ranges += i until i + p.length
                i = folded.indexOf(p, i + 1)
            }
        }
        return merge(ranges)
    }

    private fun merge(ranges: List<IntRange>): List<IntRange> {
        val sorted = ranges.sortedBy { it.first }
        val out = mutableListOf<IntRange>()
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last + 1) out[out.size - 1] = last.first..maxOf(last.last, r.last) else out += r
        }
        return out
    }
}
