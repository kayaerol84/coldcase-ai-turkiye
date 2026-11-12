package tr.coldcase.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tr.coldcase.api.TestPdfs.BlankPage
import tr.coldcase.api.TestPdfs.ImagePage
import tr.coldcase.api.TestPdfs.TextPage
import tr.coldcase.api.processing.DocumentProcessor
import tr.coldcase.api.processing.JobQueue
import tr.coldcase.api.processing.JobType
import tr.coldcase.api.processing.OcrEngine
import java.nio.file.Files
import java.util.UUID

class DocumentApiTest : ApiTestBase() {
    @Autowired lateinit var processor: DocumentProcessor
    @Autowired lateinit var ocr: OcrEngine
    @Autowired lateinit var queue: JobQueue

    private val transcript = listOf(
        "KOMİSYON BAŞKANI — Sayın Tuna, diskler konusunda ne söylemek istersiniz?",
        "ILGAZ TUNA — Hatırladığım kadarıyla evde bir inceleme yapıldı.",
        "Telefon kayıtlarının dosyaya ulaşmadığı ifade edildi.",
    )

    private fun upload(owner: TestUser, caseId: String, bytes: ByteArray, name: String = "cilt2.pdf"): ResultActions =
        mvc.perform(multipart("/api/v1/cases/$caseId/documents").file(MockMultipartFile("file", name, "application/pdf", bytes)).asUser(owner))

    private fun uploadOk(owner: TestUser, caseId: String, bytes: ByteArray): Pair<String, UUID> {
        val res = upload(owner, caseId, bytes).andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("PROCESSING")).andReturn()
        val body = readJson(res.response.contentAsString)
        return body["id"].asText() to UUID.fromString(body["file"]["versionId"].asText())
    }

    private fun doc(owner: TestUser, id: String) =
        readJson(mvc.perform(get("/api/v1/documents/$id").asUser(owner)).andExpect(status().isOk).andReturn().response.contentAsString)

    private fun page(owner: TestUser, id: String, n: Int) =
        readJson(mvc.perform(get("/api/v1/documents/$id/pages/$n").asUser(owner)).andExpect(status().isOk).andReturn().response.contentAsString)

    @Test
    fun `text pdf is extracted page by page with printed labels and processing is asynchronous`() {
        val owner = TestUser()
        val caseId = createCase(owner)
        val pdf = TestPdfs.build(listOf(TextPage(transcript, header = "1426"), TextPage(transcript, header = "- 1427 -"), BlankPage))
        val (docId, versionId) = uploadOk(owner, caseId, pdf)

        // Nothing is extracted until the worker runs.
        assertThat(doc(owner, docId)["pages"]["total"].asInt()).isZero()
        processor.drain(versionId)

        val d = doc(owner, docId)
        assertThat(d["file"]["pageCount"].asInt()).isEqualTo(3)
        assertThat(d["file"]["sha256"].asText()).hasSize(64)
        assertThat(d["pages"]["total"].asInt()).isEqualTo(3)
        assertThat(d["pages"]["extracted"].asInt()).isEqualTo(2)

        val p1 = page(owner, docId, 1)
        assertThat(p1["status"].asText()).isEqualTo("EXTRACTED")
        assertThat(p1["method"].asText()).isEqualTo("EMBEDDED_TEXT")
        assertThat(p1["printedPageLabel"].asText()).isEqualTo("1426")
        assertThat(p1["printedLabelSource"].asText()).isEqualTo("HEADER_FOOTER")
        assertThat(p1["embeddedText"].asText()).contains("Telefon")
        assertThat(p1["quality"].asDouble()).isGreaterThan(0.8)
        assertThat(p1["extractorVersion"].asText()).startsWith("pdfbox-")
        assertThat(p1.has("ocrText")).isFalse()
        if (TestPdfs.hasTurkishFont) assertThat(p1["embeddedText"].asText()).contains("kayıtlarının", "ILGAZ TUNA")
        assertThat(page(owner, docId, 2)["printedPageLabel"].asText()).isEqualTo("1427")

        // The blank page is never silently dropped: it is flagged and OCR'd (or left flagged if OCR is unavailable).
        val p3 = page(owner, docId, 3)
        if (ocr.available) {
            assertThat(p3["status"].asText()).isEqualTo("OCR_DONE")
            assertThat(d["status"].asText()).isEqualTo("READY")
        } else {
            assertThat(p3["status"].asText()).isEqualTo("NEEDS_OCR")
            assertThat(d["status"].asText()).isEqualTo("READY_WITH_WARNINGS")
            assertThat(d["statusDetail"].asText()).isEqualTo("OCR_UNAVAILABLE")
        }
    }

    @Test
    fun `pdf page labels are used when no number is printed on the page`() {
        val owner = TestUser()
        val caseId = createCase(owner)
        val (docId, versionId) = uploadOk(owner, caseId, TestPdfs.build(listOf(TextPage(transcript), TextPage(transcript)), labelStart = 1026))
        processor.drain(versionId)
        val p2 = page(owner, docId, 2)
        assertThat(p2["pdfPageNumber"].asInt()).isEqualTo(2)
        assertThat(p2["printedPageLabel"].asText()).isEqualTo("1027")
        assertThat(p2["printedLabelSource"].asText()).isEqualTo("PDF_PAGE_LABELS")
    }

    @Test
    fun `scanned page is OCR'd with Tesseract and OCR text is kept separate from embedded text`() {
        assumeTrue(ocr.available, "tesseract not installed")
        val owner = TestUser()
        val caseId = createCase(owner)
        val scanned = ImagePage(listOf("1082", "", "NAZMI OZTURK - Disklerin teslim", "edildigini hatirliyorum. Komisyon", "kayitlari inceledi."))
        val (docId, versionId) = uploadOk(owner, caseId, TestPdfs.build(listOf(scanned)))
        processor.drain(versionId)

        val p = page(owner, docId, 1)
        assertThat(p["status"].asText()).isEqualTo("OCR_DONE")
        assertThat(p["method"].asText()).isEqualTo("OCR")
        assertThat(p.has("embeddedText")).isFalse()
        assertThat(p["ocrText"].asText()).contains("Komisyon", "Disklerin", "inceledi")
        assertThat(p["ocrEngine"].asText()).isEqualTo("tesseract")
        assertThat(p["ocrEngineVersion"].asText()).isNotBlank()
        assertThat(p["ocrConfidence"].asDouble()).isGreaterThan(0.5)
        assertThat(p["printedPageLabel"].asText()).isEqualTo("1082")
        assertThat(p["printedLabelSource"].asText()).isEqualTo("OCR_HEADER_FOOTER")
        assertThat(doc(owner, docId)["status"].asText()).isEqualTo("READY")
    }

    @Test
    fun `invalid uploads are rejected by content with stable codes`() {
        val owner = TestUser()
        val caseId = createCase(owner)
        upload(owner, caseId, "hello, not a pdf".toByteArray(), "notes.pdf")
            .andExpect(status().isUnsupportedMediaType).andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"))
        upload(owner, caseId, "%PDF-1.7\n garbage garbage".toByteArray())
            .andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.code").value("CORRUPT_PDF"))
        upload(owner, caseId, TestPdfs.build(listOf(TextPage(transcript)), userPassword = "secret"))
            .andExpect(status().isUnprocessableEntity).andExpect(jsonPath("$.code").value("ENCRYPTED_PDF"))
        upload(owner, caseId, ByteArray(0)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("EMPTY_FILE"))

        mvc.perform(get("/api/v1/cases/$caseId/documents").asUser(owner)).andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `duplicate upload in the same case is rejected and points at the existing document`() {
        val owner = TestUser()
        val caseId = createCase(owner)
        val pdf = TestPdfs.build(listOf(TextPage(transcript)))
        val (docId, _) = uploadOk(owner, caseId, pdf)
        upload(owner, caseId, pdf).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DUPLICATE_DOCUMENT"))
            .andExpect(jsonPath("$.existingDocumentId").value(docId))
        // The same file in another case is a separate document.
        uploadOk(owner, createCase(owner), pdf)
    }

    @Test
    fun `viewers can read but not upload, outsiders get 404 everywhere`() {
        val owner = TestUser()
        val viewer = TestUser()
        val outsider = TestUser()
        val caseId = createCase(owner)
        addMember(caseId, owner, viewer, "VIEWER")
        val (docId, versionId) = uploadOk(owner, caseId, TestPdfs.build(listOf(TextPage(transcript))))
        processor.drain(versionId)

        upload(viewer, caseId, TestPdfs.build(listOf(TextPage(transcript)))).andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/documents/$docId/reprocess").asUser(viewer)).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/cases/$caseId/documents").asUser(viewer)).andExpect(jsonPath("$.length()").value(1))
        mvc.perform(get("/api/v1/documents/$docId/pages/1").asUser(viewer)).andExpect(status().isOk)

        upload(outsider, caseId, TestPdfs.build(listOf(TextPage(transcript)))).andExpect(status().isNotFound)
        for (path in listOf("/cases/$caseId/documents", "/documents/$docId", "/documents/$docId/pages", "/documents/$docId/pages/1", "/documents/$docId/file")) {
            mvc.perform(get("/api/v1$path").asUser(outsider)).andExpect(status().isNotFound)
        }
        mvc.perform(get("/api/v1/documents/${UUID.randomUUID()}").asUser(owner)).andExpect(status().isNotFound)
    }

    @Test
    fun `original file is returned byte-for-byte and access is audited`() {
        val owner = TestUser()
        val caseId = createCase(owner)
        val pdf = TestPdfs.build(listOf(TextPage(transcript)))
        val (docId, _) = uploadOk(owner, caseId, pdf)
        val res = mvc.perform(get("/api/v1/documents/$docId/file").param("disposition", "inline").asUser(owner))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andReturn()
        res.getAsyncResult()
        assertThat(res.response.contentAsByteArray).isEqualTo(pdf)
        val actions = jdbc.sql("SELECT action FROM audit_events WHERE case_id = :c").param("c", UUID.fromString(caseId)).query(String::class.java).list()
        assertThat(actions).contains("DOCUMENT_UPLOADED", "DOCUMENT_VIEWED")
    }

    @Test
    fun `tampered original fails the integrity check instead of being processed`() {
        val owner = TestUser()
        val caseId = createCase(owner)
        val (docId, versionId) = uploadOk(owner, caseId, TestPdfs.build(listOf(TextPage(transcript))))
        val sha = doc(owner, docId)["file"]["sha256"].asText()
        val stored = storageRoot.resolve("cases/$caseId/originals/$sha.pdf").toFile()
        assertThat(Files.getPosixFilePermissions(stored.toPath())).doesNotContain(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)
        stored.setWritable(true)
        Files.write(stored.toPath(), TestPdfs.build(listOf(TextPage(listOf("altered")))))

        processor.drain(versionId)
        val d = doc(owner, docId)
        assertThat(d["status"].asText()).isEqualTo("FAILED")
        assertThat(d["statusDetail"].asText()).isEqualTo("INTEGRITY_CHECK_FAILED")
        assertThat(d["pages"]["total"].asInt()).isZero()
    }

    @Test
    fun `reprocessing is idempotent and validates page numbers`() {
        val owner = TestUser()
        val caseId = createCase(owner)
        val (docId, versionId) = uploadOk(owner, caseId, TestPdfs.build(listOf(TextPage(transcript), TextPage(transcript))))
        mvc.perform(post("/api/v1/documents/$docId/reprocess").asUser(owner))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PROCESSING_IN_PROGRESS"))
        processor.drain(versionId)

        mvc.perform(post("/api/v1/documents/$docId/reprocess").asUser(owner)).andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("PROCESSING"))
        processor.drain(versionId)
        assertThat(doc(owner, docId)["pages"]["total"].asInt()).isEqualTo(2)
        assertThat(doc(owner, docId)["status"].asText()).isEqualTo("READY")

        mvc.perform(
            post("/api/v1/documents/$docId/reprocess").asUser(owner).contentType(MediaType.APPLICATION_JSON).content(json(mapOf("pages" to listOf(1, 9))))
        ).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_PAGES"))

        if (ocr.available) {
            mvc.perform(
                post("/api/v1/documents/$docId/reprocess").asUser(owner).contentType(MediaType.APPLICATION_JSON).content(json(mapOf("pages" to listOf(2))))
            ).andExpect(status().isAccepted)
            processor.drain(versionId)
            val p2 = page(owner, docId, 2)
            assertThat(p2["method"].asText()).isEqualTo("OCR")
            assertThat(p2["embeddedText"].asText()).contains("Telefon") // embedded text is kept alongside OCR text
        }
    }

    @Test
    fun `failed jobs are retried with backoff and then fail permanently`() {
        val owner = TestUser()
        val caseId = createCase(owner)
        val (_, versionId) = uploadOk(owner, caseId, TestPdfs.build(listOf(TextPage(transcript))))
        processor.drain(versionId)
        val jobId = queue.enqueue(UUID.fromString(caseId), versionId, JobType.OCR_PAGES, mapOf("pages" to emptyList<Int>()))
        jdbc.sql("UPDATE processing_jobs SET max_attempts = 2 WHERE id = :id").param("id", jobId).update()

        val first = queue.claim("test", java.time.Duration.ofMinutes(5), versionId)!!
        queue.fail(first, "TRANSIENT: boom", retryable = true)
        val row = jdbc.sql("SELECT status, attempts, run_after > now() AS delayed FROM processing_jobs WHERE id = :id").param("id", jobId)
            .query { rs, _ -> Triple(rs.getString(1), rs.getInt(2), rs.getBoolean(3)) }.single()
        assertThat(row).isEqualTo(Triple("QUEUED", 1, true))
        assertThat(queue.claim("test", java.time.Duration.ofMinutes(5), versionId)).isNull() // not yet due

        jdbc.sql("UPDATE processing_jobs SET run_after = now() WHERE id = :id").param("id", jobId).update()
        val second = queue.claim("test", java.time.Duration.ofMinutes(5), versionId)!!
        queue.fail(second, "TRANSIENT: boom again", retryable = true)
        val status = jdbc.sql("SELECT status FROM processing_jobs WHERE id = :id").param("id", jobId).query(String::class.java).single()
        assertThat(status).isEqualTo("FAILED")
    }
}
