package tr.coldcase.api

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tr.coldcase.api.config.AppProperties
import tr.coldcase.api.embeddings.EmbeddingUnavailableException
import tr.coldcase.api.embeddings.TeiEmbeddingProvider
import tr.coldcase.api.indexing.Chunker
import tr.coldcase.api.indexing.PageText
import tr.coldcase.api.search.TurkishText
import java.net.InetSocketAddress
import java.util.UUID

class RetrievalUnitTest {
    private fun page(n: Int, text: String) = PageText(UUID.randomUUID(), n, text, "EMBEDDED_TEXT")

    @Test
    fun `fold maps Turkish letters one to one`() {
        val s = "İSTANBUL IŞIK Öztürk ÇĞŞÜ âîû"
        assertThat(TurkishText.fold(s)).isEqualTo("istanbul isik ozturk cgsu aiu")
        assertThat(TurkishText.fold(s)).hasSameSizeAs(s)
    }

    @Test
    fun `highlights match suffixed and undotted forms`() {
        val text = "Telefon kayıtlarının dosyaya ulaşmadığı ÖZTÜRK tarafından söylendi."
        val hl = TurkishText.highlights(text, TurkishText.queryTerms("kayıt ozturk"))
        assertThat(hl.map { text.substring(it) }).containsExactly("kayıtlarının", "ÖZTÜRK")
    }

    @Test
    fun `chunker splits at speaker changes and never crosses pages`() {
        val chunks = Chunker().chunk(
            listOf(
                page(1, "1426\nBAŞKAN — Soru şu.\nILGAZ TUNA (Emniyet Genel Müdürlüğü) — Cevap şu.\nDevamı burada."),
                page(2, "devam eden cümle.\nTÜLAY KAPLAN (İstanbul) – Bir sorum var."),
            )
        )
        assertThat(chunks.map { it.pdfPageNumber to it.speaker }).containsExactly(
            // The bare printed page number "1426" carries no searchable text and is dropped.
            1 to "BAŞKAN", 1 to "ILGAZ TUNA", 2 to "ILGAZ TUNA", 2 to "TÜLAY KAPLAN",
        )
        assertThat(chunks[2].speakerFromPreviousPage).isTrue()
        assertThat(chunks[1].text).isEqualTo("ILGAZ TUNA (Emniyet Genel Müdürlüğü) — Cevap şu.\nDevamı burada.")
    }

    @Test
    fun `long turns are packed into bounded overlapping chunks with exact offsets`() {
        val sentence = "Komisyon bu konuda ayrıntılı bir inceleme yaptığını ve kayıtları istediğini belirtti. "
        val text = "BAŞKAN — " + sentence.repeat(120)
        val chunks = Chunker(targetTokens = 200, maxTokens = 300, overlapTokens = 20).chunk(listOf(page(1, text)))
        assertThat(chunks.size).isGreaterThan(5)
        chunks.forEach {
            assertThat(it.tokenEstimate).isLessThanOrEqualTo(300)
            assertThat(text.substring(it.charStart, it.charEnd)).isEqualTo(it.text)
            assertThat(it.speaker).isEqualTo("BAŞKAN")
        }
        // Consecutive chunks overlap a little and together cover the turn.
        chunks.zipWithNext().forEach { (a, b) -> assertThat(b.charStart).isLessThan(a.charEnd).isGreaterThan(a.charStart) }
        assertThat(chunks.last().charEnd).isEqualTo(text.trimEnd().length)
        assertThat(chunks.first().text).endsWith(".")
    }

    @Test
    fun `headings in capitals are not mistaken for speakers`() {
        val chunks = Chunker().chunk(listOf(page(1, "TBMM — 2 —\nA — madde\nTUTANAK DERGİSİ\nmetin")))
        assertThat(chunks.mapNotNull { it.speaker }).doesNotContain("A")
    }

    private fun tei(info: String, vectors: String, expectKey: String = "s3cret"): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val authorised = ex.requestHeaders.getFirst("Authorization") == "Bearer $expectKey"
            val (code, body) = when {
                !authorised -> 401 to "{}"
                ex.requestURI.path == "/info" -> 200 to info
                ex.requestURI.path == "/embed" -> 200 to vectors
                else -> 404 to "{}"
            }
            ex.responseHeaders.add("Content-Type", "application/json")
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}"
    }

    private fun props(url: String, key: String = "s3cret", revision: String = "abc123") = AppProperties(
        embeddings = AppProperties.Embeddings(provider = "tei", modelId = "BAAI/bge-m3", modelRevision = revision, dims = 3,
            tei = AppProperties.Tei(url = url, apiKey = key)),
    )

    @Test
    fun `tei client authenticates and checks the served model and dimensions`() {
        val (server, url) = tei("""{"model_id":"BAAI/bge-m3","model_sha":"abc123"}""", "[[0.1,0.2,0.3],[0.4,0.5,0.6]]")
        try {
            val p = TeiEmbeddingProvider(props(url))
            assertThat(p.available()).isTrue()
            assertThat(p.embedDocuments(listOf("a", "b"))).hasSize(2)

            assertThat(TeiEmbeddingProvider(props(url, key = "wrong")).available()).isFalse()
            assertThat(TeiEmbeddingProvider(props(url, revision = "other")).available()).isFalse()
            assertThatThrownBy { TeiEmbeddingProvider(props(url, revision = "other")).embedQuery("x") }
                .isInstanceOf(EmbeddingUnavailableException::class.java)
        } finally {
            server.stop(0)
        }
        val (swapped, url2) = tei("""{"model_id":"some/other-model","model_sha":"abc123"}""", "[[0.1,0.2,0.3]]")
        try {
            assertThat(TeiEmbeddingProvider(props(url2)).available()).isFalse()
        } finally {
            swapped.stop(0)
        }
        val (wrongDims, url3) = tei("""{"model_id":"BAAI/bge-m3","model_sha":"abc123"}""", "[[0.1,0.2]]")
        try {
            assertThatThrownBy { TeiEmbeddingProvider(props(url3)).embedQuery("x") }.isInstanceOf(IllegalStateException::class.java)
        } finally {
            wrongDims.stop(0)
        }
    }
}
