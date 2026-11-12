package tr.coldcase.api

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tr.coldcase.api.TestPdfs.TextPage
import tr.coldcase.api.processing.DocumentProcessor
import tr.coldcase.api.search.TurkishText
import java.util.UUID

class SearchApiTest : ApiTestBase() {
    @Autowired lateinit var processor: DocumentProcessor
    @Autowired lateinit var fake: FakeEmbeddingProvider

    @AfterEach
    fun restore() { fake.up = true }

    private val hearing = listOf(
        TextPage(
            listOf(
                "BAŞKAN — Telefon kayıtlarının komisyona neden ulaşmadığını soruyorum.",
                "ILGAZ TUNA (Emniyet Genel Müdürlüğü) — Hatırladığım kadarıyla diskler evden alındı.",
                "Bu konuda yazılı bir talep olduğunu sanmıyorum.",
            ),
            header = "1426",
        ),
        TextPage(listOf("ve inceleme sırasında başka bir belge bulunmadı, efendim."), header = "1427"),
        TextPage(listOf("NAZMİ ÖZTÜRK — Diskleri ben teslim etmedim, tutanak da görmedim."), header = "1082"),
    )

    private fun ingest(owner: TestUser, caseId: String, pages: List<TextPage> = hearing): String {
        val res = mvc.perform(
            multipart("/api/v1/cases/$caseId/documents")
                .file(MockMultipartFile("file", "tutanak.pdf", "application/pdf", TestPdfs.build(pages))).asUser(owner)
        ).andExpect(status().isAccepted).andReturn()
        val body = readJson(res.response.contentAsString)
        processor.drain(UUID.fromString(body["file"]["versionId"].asText()))
        return body["id"].asText()
    }

    private fun search(user: TestUser, caseId: String, body: Map<String, Any?>): JsonNode =
        readJson(
            mvc.perform(post("/api/v1/cases/$caseId/search").asUser(user).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk).andReturn().response.contentAsString
        )

    private fun pagesOf(r: JsonNode) = r["results"].map { it["pdfPageNumber"].asInt() }

    @Test
    fun `indexing runs after extraction and records chunker and model`() {
        val owner = TestUser()
        val caseId = createCase(owner)
        val docId = ingest(owner, caseId)
        mvc.perform(get("/api/v1/documents/$docId").asUser(owner))
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.index.status").value("INDEXED"))
            .andExpect(jsonPath("$.index.chunkerVersion").value("page-speaker-v1"))
            .andExpect(jsonPath("$.index.embeddingModel").value("test/hashing-256"))
            .andExpect(jsonPath("$.index.segmentCount").value(4))
    }

    @Test
    fun `keyword search handles Turkish suffixes, missing diacritics and dotless i`() {
        val owner = TestUser()
        val caseId = createCase(owner)
        ingest(owner, caseId)
        assertThat(pagesOf(search(owner, caseId, mapOf("query" to "kayıt", "mode" to "KEYWORD")))).containsExactly(1)
        assertThat(pagesOf(search(owner, caseId, mapOf("query" to "Ozturk", "mode" to "KEYWORD")))).containsExactly(3)
        assertThat(pagesOf(search(owner, caseId, mapOf("query" to "ılgaz tuna", "mode" to "KEYWORD")))).containsExactly(1)
        assertThat(search(owner, caseId, mapOf("query" to "kayıt -telefon", "mode" to "KEYWORD"))["results"]).isEmpty()
    }

    @Test
    fun `exact phrase search is diacritic-insensitive and highlights the phrase`() {
        val owner = TestUser()
        val caseId = createCase(owner)
        ingest(owner, caseId)
        val r = search(owner, caseId, mapOf("query" to "\"diskler evden alindi\"", "mode" to "EXACT"))
        assertThat(pagesOf(r)).containsExactly(1)
        val hit = r["results"][0]
        val h = hit["highlights"][0]
        assertThat(hit["text"].asText().substring(h["start"].asInt(), h["end"].asInt())).isEqualTo("diskler evden alındı")
        assertThat(hit["matchedBy"].map { it.asText() }).containsExactly("EXACT_PHRASE")
        assertThat(search(owner, caseId, mapOf("query" to "diskler evde yandı", "mode" to "EXACT"))["results"]).isEmpty()
    }

    @Test
    fun `hybrid answers natural questions, prefers quoted phrases and reports provenance`() {
        val owner = TestUser()
        val caseId = createCase(owner)
        val docId = ingest(owner, caseId)
        val r = search(owner, caseId, mapOf("query" to "telefon kayıtları komisyona neden ulaşmadı"))
        assertThat(r["modeUsed"].asText()).isEqualTo("HYBRID")
        assertThat(r["semanticAvailable"].asBoolean()).isTrue()
        val top = r["results"][0]
        assertThat(top["pdfPageNumber"].asInt()).isEqualTo(1)
        assertThat(top["printedPageLabel"].asText()).isEqualTo("1426")
        assertThat(top["documentId"].asText()).isEqualTo(docId)
        assertThat(top["sourceKind"].asText()).isEqualTo("EMBEDDED_TEXT")
        assertThat(top["matchedBy"].map { it.asText() }).contains("KEYWORD", "SEMANTIC")
        assertThat(top["diagnostics"]["fusedScore"].asDouble()).isPositive()
        // Highlights point at real text in the segment.
        top["highlights"].forEach {
            val word = top["text"].asText().substring(it["start"].asInt(), it["end"].asInt())
            assertThat(TurkishText.fold(word)).matches("(telef|kayit|komis|ulasm).*")
        }
        // Offsets refer to the page text.
        val page = readJson(mvc.perform(get("/api/v1/documents/$docId/pages/1").asUser(owner)).andReturn().response.contentAsString)
        assertThat(page["embeddedText"].asText().substring(top["charStart"].asInt(), top["charEnd"].asInt())).isEqualTo(top["text"].asText())

        val quoted = search(owner, caseId, mapOf("query" to "diskler \"teslim etmedim\""))
        assertThat(quoted["results"][0]["pdfPageNumber"].asInt()).isEqualTo(3)
        assertThat(quoted["results"][0]["matchedBy"].map { it.asText() }).contains("EXACT_PHRASE")
    }

    @Test
    fun `speaker turns are separate segments and a turn continuing on the next page keeps its speaker`() {
        val owner = TestUser()
        val caseId = createCase(owner)
        ingest(owner, caseId)
        val r = search(owner, caseId, mapOf("query" to "inceleme belge bulunmadı", "mode" to "KEYWORD"))
        val p2 = r["results"].first { it["pdfPageNumber"].asInt() == 2 }
        assertThat(p2["speaker"].asText()).isEqualTo("ILGAZ TUNA")
        assertThat(p2["speakerFromPreviousPage"].asBoolean()).isTrue()

        val speakers = search(owner, caseId, mapOf("query" to "telefon kayıtları diskler"))["results"]
            .filter { it["pdfPageNumber"].asInt() == 1 }.map { it["speaker"].asText() }
        assertThat(speakers).contains("BAŞKAN")
        assertThat(search(owner, caseId, mapOf("query" to "diskler", "filters" to mapOf("speaker" to "ozturk")))["results"].map { it["speaker"].asText() })
            .containsOnly("NAZMİ ÖZTÜRK")
    }

    @Test
    fun `filters restrict by document and page range`() {
        val owner = TestUser()
        val caseId = createCase(owner)
        val d1 = ingest(owner, caseId)
        ingest(owner, caseId, listOf(TextPage(listOf("BAŞKAN — Diskler başka bir tutanakta da geçiyor."))))
        assertThat(search(owner, caseId, mapOf("query" to "diskler", "mode" to "KEYWORD"))["results"].map { it["documentId"].asText() }.toSet()).hasSize(2)
        val onlyD1 = search(owner, caseId, mapOf("query" to "diskler", "mode" to "KEYWORD", "filters" to mapOf("documentIds" to listOf(d1))))
        assertThat(onlyD1["results"].map { it["documentId"].asText() }).containsOnly(d1)
        val range = search(owner, caseId, mapOf("query" to "diskler", "mode" to "KEYWORD", "filters" to mapOf("documentIds" to listOf(d1), "pdfPageFrom" to 3, "pdfPageTo" to 3)))
        assertThat(pagesOf(range)).containsOnly(3)
    }

    @Test
    fun `search never crosses cases and requires membership`() {
        val owner = TestUser()
        val other = TestUser()
        val viewer = TestUser()
        val caseA = createCase(owner)
        val caseB = createCase(other)
        ingest(owner, caseA)
        ingest(other, caseB)
        addMember(caseA, owner, viewer, "VIEWER")

        for (mode in listOf("HYBRID", "KEYWORD", "SEMANTIC", "EXACT")) {
            val r = search(owner, caseA, mapOf("query" to "diskler", "mode" to mode))
            assertThat(r["results"]).isNotEmpty
            val docCases = r["results"].map { it["documentId"].asText() }.toSet().map { d ->
                readJson(mvc.perform(get("/api/v1/documents/$d").asUser(owner)).andExpect(status().isOk).andReturn().response.contentAsString)["caseId"].asText()
            }
            assertThat(docCases).containsOnly(caseA)
        }
        assertThat(search(viewer, caseA, mapOf("query" to "diskler"))["results"]).isNotEmpty
        mvc.perform(post("/api/v1/cases/$caseA/search").asUser(other).contentType(MediaType.APPLICATION_JSON).content(json(mapOf("query" to "diskler"))))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `when the embedding server is down search falls back to keywords and indexing retries`() {
        val owner = TestUser()
        val caseId = createCase(owner)
        ingest(owner, caseId)
        fake.up = false
        val r = search(owner, caseId, mapOf("query" to "diskler"))
        assertThat(r["mode"].asText()).isEqualTo("HYBRID")
        assertThat(r["modeUsed"].asText()).isEqualTo("KEYWORD")
        assertThat(r["semanticAvailable"].asBoolean()).isFalse()
        assertThat(r["results"]).isNotEmpty

        // A new upload while the server is down: the index job is retried, then indexed for keywords only.
        val res = mvc.perform(
            multipart("/api/v1/cases/$caseId/documents")
                .file(MockMultipartFile("file", "b.pdf", "application/pdf", TestPdfs.build(listOf(TextPage(listOf("Tanık ifadesi tutanağa geçti.")))))).asUser(owner)
        ).andReturn()
        val body = readJson(res.response.contentAsString)
        val versionId = UUID.fromString(body["file"]["versionId"].asText())
        processor.runNext(versionId) // extraction
        jdbc.sql("UPDATE processing_jobs SET max_attempts = 2 WHERE document_version_id = :v AND job_type = 'INDEX_DOCUMENT'").param("v", versionId).update()
        processor.runNext(versionId) // index attempt 1: server down -> retry later
        val queued = jdbc.sql("SELECT status FROM processing_jobs WHERE document_version_id = :v AND job_type = 'INDEX_DOCUMENT'").param("v", versionId).query(String::class.java).single()
        assertThat(queued).isEqualTo("QUEUED")
        jdbc.sql("UPDATE processing_jobs SET run_after = now() WHERE document_version_id = :v").param("v", versionId).update()
        processor.runNext(versionId) // final attempt: keyword-only
        mvc.perform(get("/api/v1/documents/${body["id"].asText()}").asUser(owner))
            .andExpect(jsonPath("$.index.status").value("INDEXED"))
            .andExpect(jsonPath("$.status").value("READY_WITH_WARNINGS"))
            .andExpect(jsonPath("$.statusDetail").value("SEMANTIC_INDEX_MISSING"))
        assertThat(pagesOf(search(owner, caseId, mapOf("query" to "tanık ifadesi", "mode" to "KEYWORD")))).isNotEmpty
    }

    @Test
    fun `reindexing reuses embeddings for unchanged text`() {
        val owner = TestUser()
        val caseId = createCase(owner)
        val docId = ingest(owner, caseId)
        val before = fake.documentsEmbedded
        mvc.perform(post("/api/v1/documents/$docId/reprocess").asUser(owner)).andExpect(status().isAccepted)
        val versionId = readJson(mvc.perform(get("/api/v1/documents/$docId").asUser(owner)).andReturn().response.contentAsString)["file"]["versionId"].asText()
        processor.drain(UUID.fromString(versionId))
        assertThat(fake.documentsEmbedded).isEqualTo(before)
        mvc.perform(get("/api/v1/documents/$docId").asUser(owner)).andExpect(jsonPath("$.index.segmentCount").value(4))
    }

    @Test
    fun `query validation`() {
        val owner = TestUser()
        val caseId = createCase(owner)
        mvc.perform(post("/api/v1/cases/$caseId/search").asUser(owner).contentType(MediaType.APPLICATION_JSON).content(json(mapOf("query" to " "))))
            .andExpect(status().isBadRequest)
        mvc.perform(post("/api/v1/cases/$caseId/search").asUser(owner).contentType(MediaType.APPLICATION_JSON).content(json(mapOf("query" to "a".repeat(501)))))
            .andExpect(status().isBadRequest)
        // Characters that are special in tsquery or LIKE are treated as text.
        for (q in listOf("a & b | !c", "100%_done", "'; DROP TABLE cases; --", ":* <-> \\")) {
            for (mode in listOf("HYBRID", "KEYWORD", "EXACT")) search(owner, caseId, mapOf("query" to q, "mode" to mode))
        }
    }

    @Test
    fun `page image renders the original and page reports are recorded`() {
        val owner = TestUser()
        val researcher = TestUser()
        val viewer = TestUser()
        val outsider = TestUser()
        val caseId = createCase(owner)
        addMember(caseId, owner, researcher, "RESEARCHER")
        addMember(caseId, owner, viewer, "VIEWER")
        val docId = ingest(owner, caseId)

        val png = mvc.perform(get("/api/v1/documents/$docId/pages/1/image").asUser(viewer))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        assertThat(png.take(4)).containsExactly(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        mvc.perform(get("/api/v1/documents/$docId/pages/9/image").asUser(owner)).andExpect(status().isNotFound)
        mvc.perform(get("/api/v1/documents/$docId/pages/1/image").asUser(outsider)).andExpect(status().isNotFound)

        val report = mapOf("kind" to "WRONG_PAGE_LABEL", "note" to "Basılı sayfa 1425 olmalı")
        mvc.perform(post("/api/v1/documents/$docId/pages/1/reports").asUser(viewer).contentType(MediaType.APPLICATION_JSON).content(json(report)))
            .andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/documents/$docId/pages/1/reports").asUser(researcher).contentType(MediaType.APPLICATION_JSON).content(json(report)))
            .andExpect(status().isCreated).andExpect(jsonPath("$.pdfPageNumber").value(1))
        mvc.perform(get("/api/v1/documents/$docId/reports").asUser(viewer))
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].kind").value("WRONG_PAGE_LABEL"))
        val actions = jdbc.sql("SELECT action FROM audit_events WHERE case_id = :c").param("c", UUID.fromString(caseId)).query(String::class.java).list()
        assertThat(actions).contains("PAGE_REPORTED")
    }
}
