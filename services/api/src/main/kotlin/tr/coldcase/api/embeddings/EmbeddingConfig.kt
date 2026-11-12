package tr.coldcase.api.embeddings

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tr.coldcase.api.config.AppProperties

@Configuration
class EmbeddingConfig {
    @Bean
    fun embeddingProvider(props: AppProperties): EmbeddingProvider = when (props.embeddings.provider) {
        "none" -> NoEmbeddingProvider()
        "tei" -> TeiEmbeddingProvider(props)
        else -> error("Unknown app.embeddings.provider '${props.embeddings.provider}' (allowed: none, tei)")
    }
}

/** pgvector text literal, e.g. [0.1,0.2]. */
fun FloatArray.toPgVector(): String = joinToString(",", "[", "]")
