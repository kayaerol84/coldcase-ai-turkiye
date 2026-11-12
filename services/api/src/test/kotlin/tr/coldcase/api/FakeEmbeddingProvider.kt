package tr.coldcase.api

import tr.coldcase.api.embeddings.EmbeddingProvider
import tr.coldcase.api.embeddings.EmbeddingUnavailableException
import tr.coldcase.api.search.TurkishText
import kotlin.math.sqrt

/**
 * Deterministic test double: bag of folded 5-letter word prefixes hashed into 256 dimensions.
 * Shares vocabulary-level similarity only — enough to exercise indexing and fusion, not a model.
 */
class FakeEmbeddingProvider : EmbeddingProvider {
    @Volatile var up = true
    @Volatile var documentsEmbedded = 0

    override val modelId = "test/hashing-256"
    override val modelRevision = "test"
    override val dims = 256

    override fun available() = up

    override fun embedDocuments(texts: List<String>): List<FloatArray> {
        if (!up) throw EmbeddingUnavailableException("down")
        documentsEmbedded += texts.size
        return texts.map(::vec)
    }

    override fun embedQuery(text: String): FloatArray {
        if (!up) throw EmbeddingUnavailableException("down")
        return vec(text)
    }

    private fun vec(text: String): FloatArray {
        val v = FloatArray(dims)
        Regex("[\\p{L}\\p{N}]{3,}").findAll(TurkishText.fold(text)).forEach {
            v[Math.floorMod(it.value.take(5).hashCode(), dims)] += 1f
        }
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0) for (i in v.indices) v[i] /= norm else v[0] = 1f
        return v
    }
}
