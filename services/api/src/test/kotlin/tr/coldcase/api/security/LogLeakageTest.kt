package tr.coldcase.api.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import tr.coldcase.api.ApiTestBase
import tr.coldcase.api.FakeEmbeddingProvider
import tr.coldcase.api.TestPdfs
import tr.coldcase.api.TestPdfs.TextPage
import tr.coldcase.api.processing.DocumentProcessor
import java.util.UUID

/** Document text and search queries must never reach logs (TR §14), including on failure paths. */
@Tag("security")
@ExtendWith(OutputCaptureExtension::class)
class LogLeakageTest : ApiTestBase() {
    @Autowired lateinit var processor: DocumentProcessor
    @Autowired lateinit var fake: FakeEmbeddingProvider

    @Test
    fun `document text and queries are not logged`(output: CapturedOutput) {
        val docCanary = "KANARYABELGE" + UUID.randomUUID().toString().replace("-", "")
        val queryCanary = "KANARYASORGU" + UUID.randomUUID().toString().replace("-", "")
        val owner = TestUser()
        val caseId = createCase(owner)
        val res = mvc.perform(
            multipart("/api/v1/cases/$caseId/documents")
                .file(MockMultipartFile("file", "$docCanary.pdf", "application/pdf",
                    TestPdfs.build(listOf(TextPage(listOf("BAŞKAN — Kurgusal ifade $docCanary burada geçiyor.")))))).asUser(owner)
        ).andReturn()
        processor.drain(UUID.fromString(readJson(res.response.contentAsString)["file"]["versionId"].asText()))

        for (mode in listOf("HYBRID", "KEYWORD", "SEMANTIC", "EXACT")) {
            mvc.perform(post("/api/v1/cases/$caseId/search").asUser(owner).contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("query" to "$queryCanary $docCanary", "mode" to mode))))
        }
        // Failure paths: embedding server down, invalid query.
        fake.up = false
        try {
            mvc.perform(post("/api/v1/cases/$caseId/search").asUser(owner).contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("query" to queryCanary))))
        } finally {
            fake.up = true
        }
        mvc.perform(post("/api/v1/cases/$caseId/search").asUser(owner).contentType(MediaType.APPLICATION_JSON)
            .content(json(mapOf("query" to queryCanary.repeat(30)))))

        assertThat(output.all).doesNotContain(docCanary).doesNotContain(queryCanary)
    }
}
