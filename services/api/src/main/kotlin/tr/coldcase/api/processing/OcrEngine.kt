package tr.coldcase.api.processing

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tr.coldcase.api.config.AppProperties
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

data class OcrResult(val text: String, val meanConfidence: Double)

interface OcrEngine {
    val name: String
    /** Engine version string, or null if the engine is not installed. */
    val version: String?
    val available: Boolean get() = version != null
    fun recognize(image: BufferedImage): OcrResult
}

class OcrUnavailableException(message: String) : RuntimeException(message)

/** Runs the Tesseract CLI per page image and reads word-level confidences from its TSV output. */
@Component
class TesseractOcrEngine(private val props: AppProperties) : OcrEngine {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cfg get() = props.extraction.ocr

    override val name = "tesseract"

    override val version: String? by lazy {
        try {
            val p = ProcessBuilder(cfg.command, "--version").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0) {
                out.lineSequence().first().removePrefix("tesseract").trim().ifEmpty { "unknown" }
            } else null
        } catch (e: Exception) {
            log.warn("Tesseract is not available: {}", e.message)
            null
        }
    }

    override fun recognize(image: BufferedImage): OcrResult {
        if (!available) throw OcrUnavailableException("OCR engine '$name' is not installed")
        val png = Files.createTempFile("ocr-", ".png")
        try {
            ImageIO.write(image, "png", png.toFile())
            val p = ProcessBuilder(cfg.command, png.toString(), "stdout", "-l", cfg.languages, "--psm", "3", "tsv")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val tsv = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(cfg.timeout.toSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly()
                throw IllegalStateException("OCR timed out")
            }
            check(p.exitValue() == 0) { "OCR exited with ${p.exitValue()}" }
            return parseTsv(tsv)
        } finally {
            Files.deleteIfExists(png)
        }
    }

    companion object {
        /** Rebuilds text from Tesseract TSV, keeping line and paragraph breaks. */
        fun parseTsv(tsv: String): OcrResult {
            val sb = StringBuilder()
            val confidences = mutableListOf<Double>()
            var lastPar: String? = null
            var lastLine: String? = null
            for (row in tsv.lineSequence().drop(1)) {
                val cols = row.split('\t')
                if (cols.size < 12 || cols[0] != "5") continue // level 5 = word
                val word = cols[11].trim()
                if (word.isEmpty()) continue
                val conf = cols[10].toDoubleOrNull() ?: -1.0
                if (conf >= 0) confidences += conf
                val par = "${cols[2]}-${cols[3]}"
                val line = "$par-${cols[4]}"
                when {
                    lastPar == null -> {}
                    par != lastPar -> sb.append("\n\n")
                    line != lastLine -> sb.append('\n')
                    else -> sb.append(' ')
                }
                sb.append(word)
                lastPar = par
                lastLine = line
            }
            val mean = if (confidences.isEmpty()) 0.0 else confidences.average() / 100.0
            return OcrResult(sb.toString(), mean.coerceIn(0.0, 1.0))
        }
    }
}
