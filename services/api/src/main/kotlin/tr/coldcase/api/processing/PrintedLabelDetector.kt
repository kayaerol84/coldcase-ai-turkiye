package tr.coldcase.api.processing

/**
 * Finds a printed page number in a page's header or footer lines, e.g. "1427", "- 1427 -", "— 12 —",
 * "Sayfa 14", "S. 14". Returns null when nothing unambiguous is found: a wrong label is worse than none.
 */
object PrintedLabelDetector {
    private val patterns = listOf(
        Regex("^[\\s\\-–—.]*(\\d{1,5})[\\s\\-–—.]*$"),
        Regex("^(?:sayfa|sf\\.?|s\\.)\\s*(\\d{1,5})$", RegexOption.IGNORE_CASE),
    )

    fun detect(text: String?, edgeLines: Int = 3): String? {
        if (text.isNullOrBlank()) return null
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val candidates = (lines.take(edgeLines) + lines.takeLast(edgeLines)).distinct()
        val found = candidates.mapNotNull { line -> patterns.firstNotNullOfOrNull { it.matchEntire(line)?.groupValues?.get(1) } }
            .map { it.trimStart('0').ifEmpty { "0" } }
            .distinct()
        return found.singleOrNull()
    }
}
