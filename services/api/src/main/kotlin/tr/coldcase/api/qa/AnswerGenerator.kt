package tr.coldcase.api.qa

/**
 * Port for the language model that drafts answers (TR §6.3). Adapters live in the `llm` package and are
 * selected by app.llm.provider. The Q&A context owns the prompt, the output schema and all validation:
 * an adapter only transports a request and reports what came back.
 *
 * Sending passages to a provider sends case content to it. Only providers accepted in ADR 0002 may be wired.
 */
interface AnswerGenerator {
    /** Recorded on every answer, e.g. "anthropic". */
    val provider: String

    /** Generation settings recorded for reproducibility (model requested, effort, token limit…). No secrets. */
    val settings: Map<String, Any?>

    fun available(): Boolean

    fun generate(request: GenerationRequest): GenerationResult
}

data class GenerationRequest(
    val system: String,
    val user: String,
    /** JSON Schema the output must follow (structured output). */
    val outputSchema: Map<String, Any?>,
)

sealed interface GenerationResult {
    /** [model] is the model that actually produced the output, as reported by the provider. */
    data class Completed(val outputJson: String, val model: String, val inputTokens: Long?, val outputTokens: Long?) : GenerationResult

    /** The model declined to answer (safety refusal). Stored as an abstention, never retried with another model. */
    data class Declined(val model: String, val category: String?) : GenerationResult

    /** Transport error, timeout, truncated or unparseable output. Not stored; the caller sees 503. */
    data class Failed(val reason: String) : GenerationResult
}

/** Used when app.llm.provider=none: Q&A is switched off and answers 503 QA_UNAVAILABLE. */
class NoAnswerGenerator : AnswerGenerator {
    override val provider = "none"
    override val settings: Map<String, Any?> = emptyMap()
    override fun available() = false
    override fun generate(request: GenerationRequest): GenerationResult = GenerationResult.Failed("DISABLED")
}
