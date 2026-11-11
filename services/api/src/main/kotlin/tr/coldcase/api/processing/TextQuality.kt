package tr.coldcase.api.processing

/**
 * Heuristic score in [0, 1] for whether extracted text looks like readable Turkish/Latin text.
 * Catches broken font encodings (glyph soup, replacement characters, private-use code points).
 * It measures extraction plausibility only, never truthfulness of the content.
 */
object TextQuality {
    private const val TURKISH = "çğıöşüÇĞİÖŞÜâîûÂÎÛ"

    fun nonWhitespaceChars(text: String) = text.count { !it.isWhitespace() }

    fun score(text: String): Double {
        val chars = text.filter { !it.isWhitespace() }
        if (chars.isEmpty()) return 0.0
        var good = 0
        for (c in chars) {
            val ok = (c in 'a'..'z') || (c in 'A'..'Z') || c.isDigit() || c in TURKISH ||
                c in ".,;:!?'\"()[]-–—/%&*+=_<>«»“”‘’…§°#@" || (Character.isLetter(c) && c.code < 0x0250)
            if (ok) good++
        }
        val charScore = good.toDouble() / chars.length
        // Real prose has words; glyph soup tends to have very long or one-character "words".
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val plausibleWords = words.count { it.length in 2..30 }
        val wordScore = if (words.isEmpty()) 0.0 else plausibleWords.toDouble() / words.size
        return (0.7 * charScore + 0.3 * wordScore).coerceIn(0.0, 1.0)
    }
}
