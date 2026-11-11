package tr.coldcase.api

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.common.PDPageLabelRange
import org.apache.pdfbox.pdmodel.common.PDPageLabels
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File

/** Builds small PDFs for ingestion tests, mimicking hearing-transcript pages. */
object TestPdfs {
    private val dejaVu = File("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")
    val hasTurkishFont get() = dejaVu.exists()

    sealed interface PageSpec
    /** A page of embedded text; [header] is printed as the first line (e.g. a transcript page number). */
    data class TextPage(val lines: List<String>, val header: String? = null) : PageSpec
    data object BlankPage : PageSpec
    /** A "scanned" page: the text exists only as pixels, so it needs OCR. */
    data class ImagePage(val lines: List<String>) : PageSpec

    fun build(pages: List<PageSpec>, labelStart: Int? = null, userPassword: String? = null, unique: String = java.util.UUID.randomUUID().toString()): ByteArray {
        PDDocument().use { doc ->
            doc.documentInformation.title = "test-$unique" // makes every generated file hash-unique
            val font = if (hasTurkishFont) PDType0Font.load(doc, dejaVu) else PDType1Font(Standard14Fonts.FontName.HELVETICA)
            for (spec in pages) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                when (spec) {
                    is BlankPage -> {}
                    is TextPage -> PDPageContentStream(doc, page).use { cs ->
                        var y = 800f
                        (listOfNotNull(spec.header) + spec.lines).forEach { line ->
                            cs.beginText()
                            cs.setFont(font, 11f)
                            cs.newLineAtOffset(60f, y)
                            cs.showText(line)
                            cs.endText()
                            y -= 16f
                        }
                    }
                    is ImagePage -> PDPageContentStream(doc, page).use { cs ->
                        val img = LosslessFactory.createFromImage(doc, renderText(spec.lines))
                        cs.drawImage(img, 0f, 0f, PDRectangle.A4.width, PDRectangle.A4.height)
                    }
                }
            }
            if (labelStart != null) {
                val labels = PDPageLabels(doc)
                labels.setLabelItem(0, PDPageLabelRange().apply { style = PDPageLabelRange.STYLE_DECIMAL; start = labelStart })
                doc.documentCatalog.pageLabels = labels
            }
            if (userPassword != null) {
                doc.protect(StandardProtectionPolicy("owner-$userPassword", userPassword, AccessPermission()).apply { encryptionKeyLength = 128 })
            }
            return ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }

    /** Renders text onto an A4 bitmap at 150 DPI, like a clean scan. */
    private fun renderText(lines: List<String>): BufferedImage {
        val img = BufferedImage(1240, 1754, BufferedImage.TYPE_BYTE_GRAY)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, img.width, img.height)
        g.color = Color.BLACK
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = if (hasTurkishFont) Font.createFont(Font.TRUETYPE_FONT, dejaVu).deriveFont(30f) else Font(Font.SANS_SERIF, Font.PLAIN, 30)
        lines.forEachIndexed { i, line -> g.drawString(line, 100, 150 + i * 50) }
        g.dispose()
        return img
    }
}
