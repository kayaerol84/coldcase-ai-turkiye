package tr.coldcase.api.embeddings

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tr.coldcase.api.config.AppProperties
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Client for a self-hosted Hugging Face Text Embeddings Inference server running bge-m3.
 * - Authenticates with a shared API key.
 * - Before use, checks /info: the served model id (and revision, when pinned) must match configuration,
 *   so a swapped or tampered model cannot silently produce incompatible vectors.
 * - Never logs request or response bodies (they contain document text).
 */
class TeiEmbeddingProvider(private val props: AppProperties) : EmbeddingProvider {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cfg = props.embeddings

    override val modelId: String = cfg.modelId
    override val modelRevision: String? = cfg.modelRevision.ifBlank { null }
    override val dims: Int = cfg.dims

    private val client: RestClient = RestClient.builder()
        .baseUrl(cfg.tei.url)
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(5))
            setReadTimeout(cfg.tei.timeout)
        })
        .defaultHeaders { h -> if (cfg.tei.apiKey.isNotBlank()) h.setBearerAuth(cfg.tei.apiKey) }
        .build()

    private data class Check(val ok: Boolean, val at: Instant, val reason: String?)
    private val lastCheck = AtomicReference<Check?>(null)

    init {
        if (cfg.tei.apiKey.isBlank()) log.warn("Embedding server API key is not set; only acceptable for local development")
        if (modelRevision == null) log.warn("Embedding model revision is not pinned (app.embeddings.model-revision)")
    }

    override fun available(): Boolean {
        val c = lastCheck.get()
        // Cache healthy results for a minute and failures for 10 s so outages recover quickly.
        if (c != null && c.at.isAfter(Instant.now().minusSeconds(if (c.ok) 60 else 10))) return c.ok
        val result = try {
            val info = client.get().uri("/info").retrieve().body(JsonNode::class.java)
                ?: return record(false, "empty /info")
            val servedModel = info["model_id"]?.asText()
            val servedSha = info["model_sha"]?.asText()
            when {
                servedModel != modelId -> record(false, "server runs '$servedModel', expected '$modelId'")
                modelRevision != null && servedSha != modelRevision -> record(false, "server revision '$servedSha' is not the pinned '$modelRevision'")
                else -> record(true, null)
            }
        } catch (e: RestClientException) {
            record(false, "unreachable: ${e.javaClass.simpleName}")
        }
        return result
    }

    private fun record(ok: Boolean, reason: String?): Boolean {
        val prev = lastCheck.getAndSet(Check(ok, Instant.now(), reason))
        if (!ok && (prev == null || prev.ok || prev.reason != reason)) log.warn("Embedding server unavailable: {}", reason)
        if (ok && prev != null && !prev.ok) log.info("Embedding server available again")
        return ok
    }

    override fun embedDocuments(texts: List<String>): List<FloatArray> {
        if (!available()) throw EmbeddingUnavailableException(lastCheck.get()?.reason ?: "unavailable")
        return texts.chunked(cfg.batchSize).flatMap { embed(it) }
    }

    override fun embedQuery(text: String): FloatArray {
        if (!available()) throw EmbeddingUnavailableException(lastCheck.get()?.reason ?: "unavailable")
        return embed(listOf(text)).single()
    }

    private fun embed(batch: List<String>): List<FloatArray> {
        val body = client.post().uri("/embed")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("inputs" to batch, "normalize" to true, "truncate" to true))
            .retrieve()
            .body(Array<FloatArray>::class.java)
            ?: throw EmbeddingUnavailableException("empty response")
        check(body.size == batch.size) { "Embedding count mismatch" }
        body.forEach { check(it.size == dims) { "Expected $dims dimensions, got ${it.size}" } }
        return body.toList()
    }
}
