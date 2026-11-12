package tr.coldcase.api.security

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tr.coldcase.api.ApiTestBase
import tr.coldcase.api.TestPdfs
import tr.coldcase.api.TestPdfs.TextPage
import java.io.ByteArrayOutputStream

/** Hostile uploads (TR §16): disguised files, path tricks, active content, resource exhaustion. */
@Tag("security")
class UploadSecurityTest : ApiTestBase() {

    private fun upload(owner: TestUser, caseId: String, bytes: ByteArray, name: String): ResultActions =
        mvc.perform(multipart("/api/v1/cases/$caseId/documents").file(MockMultipartFile("file", name, "application/pdf", bytes)).asUser(owner))

    private fun pdf(build: (PDDocument) -> Unit): ByteArray =
        PDDocument().use { d -> build(d); ByteArrayOutputStream().also { d.save(it) }.toByteArray() }

    @Test
    fun `path components in the filename are stripped`() {
        val owner = TestUser()
        val caseId = createCase(owner)
        val res = upload(owner, caseId, TestPdfs.build(listOf(TextPage(listOf("Kurgusal metin.")))), "../../etc/\u0000passwd\".pdf")
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.file.originalFilename").value("passwd.pdf"))
            .andExpect(jsonPath("$.title").value("passwd"))
            .andReturn()
        val id = readJson(res.response.contentAsString)["id"].asText()
        val cd = mvc.perform(get("/api/v1/documents/$id/file").asUser(owner)).andReturn().response.getHeader("Content-Disposition")!!
        assertThat(cd).startsWith("attachment").doesNotContain("..").doesNotContain("/")
    }

    @Test
    fun `files that are not PDFs are rejected whatever their name or declared type`() {
        val owner = TestUser()
        val caseId = createCase(owner)
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) + ByteArray(64)
        upload(owner, caseId, png, "scan.pdf").andExpect(status().isUnsupportedMediaType)
        upload(owner, caseId, "<html><script>alert(1)</script></html>".toByteArray(), "page.pdf").andExpect(status().isUnsupportedMediaType)
        // Polyglot: HTML with a PDF header further in; it is not a parseable PDF.
        upload(owner, caseId, "<html><script>alert(1)</script>\n%PDF-1.7\n</html>".toByteArray(), "poly.pdf")
            .andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.code").value("CORRUPT_PDF"))
        mvc.perform(get("/api/v1/cases/$caseId/documents").asUser(owner)).andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `PDFs with active content are stored but only ever served as inert downloads`() {
        val owner = TestUser()
        val caseId = createCase(owner)
        val js = pdf { d ->
            d.addPage(PDPage())
            d.documentCatalog.openAction = PDActionJavaScript("app.alert('x')")
        }
        val id = readJson(upload(owner, caseId, js, "aktif.pdf").andExpect(status().isAccepted).andReturn().response.contentAsString)["id"].asText()
        mvc.perform(get("/api/v1/documents/$id/file").asUser(owner))
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
            .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'none'")))
    }

    @Test
    fun `documents above the page limit are rejected before processing`() {
        val owner = TestUser()
        val caseId = createCase(owner)
        val huge = pdf { d -> repeat(5001) { d.addPage(PDPage()) } }
        upload(owner, caseId, huge, "cok-sayfa.pdf")
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value("TOO_MANY_PAGES"))
    }
}
