package tr.coldcase.api.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("app")
data class AppProperties(
    val documents: Documents = Documents(),
    val storage: Storage = Storage(),
    val worker: Worker = Worker(),
    val extraction: Extraction = Extraction(),
    val embeddings: Embeddings = Embeddings(),
    val search: Search = Search(),
    val qa: Qa = Qa(),
    val llm: Llm = Llm(),
) {
    data class Qa(
        /** Questions are also the retrieval query, so this must not exceed search.maxQueryLength. */
        val maxQuestionLength: Int = 500,
        /** Passages retrieved and shown to the model per question. */
        val passages: Int = 12,
        /** Shorter quotes are rejected: they verify too easily to show that the passage supports the claim. */
        val minQuoteChars: Int = 8,
        val maxClaims: Int = 20,
    )

    data class Llm(
        /**
         * none (Q&A switched off) | anthropic (Claude API). Sending passages to a provider sends case content
         * to it: only enable a provider accepted in docs/adr/0002-llm-provider.md.
         */
        val provider: String = "none",
        val model: String = "claude-opus-5-5",
        /** low | medium | high | xhigh | max. Grounded answering with citations is intelligence-sensitive. */
        val effort: String = "high",
        val maxTokens: Int = 16000,
        val timeout: Duration = Duration.ofSeconds(180),
        val maxRetries: Int = 2,
        /** Where the provider may run inference ("us" or "global" on the Anthropic API). Empty = provider default. */
        val inferenceGeo: String = "",
        val anthropic: Anthropic = Anthropic(),
    )

    data class Anthropic(
        val apiKey: String = "",
        /** Override for tests or a gateway; empty = the SDK default. */
        val baseUrl: String = "",
    )

    data class Embeddings(
        /** none (keyword search only) | tei (self-hosted Text Embeddings Inference). Document text never leaves our infrastructure. */
        val provider: String = "none",
        val modelId: String = "BAAI/bge-m3",
        /** Pinned model commit; the server's reported revision must match or semantic search is disabled. */
        val modelRevision: String = "",
        val dims: Int = 1024,
        val batchSize: Int = 16,
        val tei: Tei = Tei(),
    )

    data class Tei(
        val url: String = "http://localhost:8082",
        /** Shared secret for the embedding server (TEI --api-key). Required outside local dev. */
        val apiKey: String = "",
        val timeout: Duration = Duration.ofSeconds(60),
    )

    data class Search(
        val maxQueryLength: Int = 500,
        val candidatesPerMethod: Int = 50,
        val defaultLimit: Int = 20,
        val maxLimit: Int = 50,
    )

    data class Documents(val maxPages: Int = 5000)

    data class Storage(
        val type: String = "filesystem",
        val filesystemRoot: String = "./data/objects",
        /** Verified local copies of originals used for page rendering. */
        val cacheDir: String = "./data/cache",
        val s3: S3 = S3(),
    )

    data class S3(
        val endpoint: String? = null,
        val region: String = "eu-central-1",
        val bucket: String = "coldcase-originals",
        val accessKey: String? = null,
        val secretKey: String? = null,
        val pathStyle: Boolean = true,
        val createBucket: Boolean = false,
        val serverSideEncryption: Boolean = true,
    )

    data class Worker(
        val enabled: Boolean = true,
        val pollInterval: Duration = Duration.ofSeconds(2),
        val lease: Duration = Duration.ofMinutes(15),
    )

    data class Extraction(
        /** Pages with fewer non-whitespace characters of embedded text are sent to OCR. */
        val minTextChars: Int = 25,
        /** Pages whose embedded text scores below this (garbled font encodings) are sent to OCR. */
        val minQuality: Double = 0.6,
        val ocrBatchSize: Int = 10,
        val ocr: Ocr = Ocr(),
    )

    data class Ocr(
        val command: String = "tesseract",
        val languages: String = "tur",
        val dpi: Int = 300,
        val timeout: Duration = Duration.ofSeconds(180),
    )
}
