package tr.coldcase.api.embeddings

/**
 * Turns text into vectors. Implementations must run on infrastructure we control: case documents are
 * sent here in full, so a third-party API would be a data transfer requiring a separate decision (ADR 0001).
 */
interface EmbeddingProvider {
    /** Null when semantic search is not configured. */
    val modelId: String?
    val modelRevision: String?
    val dims: Int

    /** True when the provider is configured, reachable and serving the expected model. */
    fun available(): Boolean

    fun embedDocuments(texts: List<String>): List<FloatArray>

    fun embedQuery(text: String): FloatArray
}

class EmbeddingUnavailableException(message: String) : RuntimeException(message)

/** Keyword search only. */
class NoEmbeddingProvider : EmbeddingProvider {
    override val modelId: String? = null
    override val modelRevision: String? = null
    override val dims = 0
    override fun available() = false
    override fun embedDocuments(texts: List<String>) = throw EmbeddingUnavailableException("Embeddings are not configured")
    override fun embedQuery(text: String) = throw EmbeddingUnavailableException("Embeddings are not configured")
}
