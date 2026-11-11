package tr.coldcase.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tr.coldcase.api.processing.JobQueue
import tr.coldcase.api.processing.PrintedLabelDetector
import tr.coldcase.api.processing.TesseractOcrEngine
import tr.coldcase.api.processing.TextQuality
import java.time.Duration

class ExtractionHeuristicsTest {
    @Test
    fun `printed labels are found in headers and footers only when unambiguous`() {
        assertThat(PrintedLabelDetector.detect("1427\nKOMİSYON BAŞKANI — Devam edelim.")).isEqualTo("1427")
        assertThat(PrintedLabelDetector.detect("Metin satırı\nikinci satır\n— 1082 —")).isEqualTo("1082")
        assertThat(PrintedLabelDetector.detect("Sayfa 14\nmetin")).isEqualTo("14")
        assertThat(PrintedLabelDetector.detect("1993 yılında olay oldu\nmetin")).isNull() // number inside a sentence
        assertThat(PrintedLabelDetector.detect("12\nmetin\nmetin\nmetin\nmetin\nmetin\nmetin\n13")).isNull() // conflicting
        assertThat(PrintedLabelDetector.detect("")).isNull()
    }

    @Test
    fun `text quality separates prose from glyph soup`() {
        assertThat(TextQuality.score("Telefon kayıtlarının dosyaya ulaşmadığı ifade edildi.")).isGreaterThan(0.9)
        assertThat(TextQuality.score("�� ")).isLessThan(0.4)
        assertThat(TextQuality.score("   ")).isZero()
    }

    @Test
    fun `tesseract tsv is rebuilt into lines and paragraphs with mean confidence`() {
        val tsv = """
            level	page_num	block_num	par_num	line_num	word_num	left	top	width	height	conf	text
            1	1	0	0	0	0	0	0	100	100	-1	
            5	1	1	1	1	1	0	0	10	10	90	Telefon
            5	1	1	1	1	2	0	0	10	10	80	kayıtları
            5	1	1	1	2	1	0	0	10	10	70	ulaşmadı.
            5	1	1	2	1	1	0	0	10	10	60	Komisyon
        """.trimIndent()
        val r = TesseractOcrEngine.parseTsv(tsv)
        assertThat(r.text).isEqualTo("Telefon kayıtları\nulaşmadı.\n\nKomisyon")
        assertThat(r.meanConfidence).isEqualTo(0.75)
    }

    @Test
    fun `backoff grows exponentially and is capped`() {
        assertThat(JobQueue.backoffFor(1)).isEqualTo(Duration.ofSeconds(5))
        assertThat(JobQueue.backoffFor(3)).isEqualTo(Duration.ofSeconds(20))
        assertThat(JobQueue.backoffFor(20)).isEqualTo(Duration.ofMinutes(10))
    }
}
