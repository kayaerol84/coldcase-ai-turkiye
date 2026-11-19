package tr.coldcase.api.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.ThinkingConfigAdaptive
import org.slf4j.LoggerFactory
import tr.coldcase.api.config.AppProperties
import tr.coldcase.api.qa.AnswerGenerator
import tr.coldcase.api.qa.GenerationRequest
import tr.coldcase.api.qa.GenerationResult

/**
 * Claude via the Anthropic API (ADR 0002). One non-streaming Messages call per question with adaptive
 * thinking and structured JSON output. Server-side model fallbacks are not enabled: an answer must come from
 * the configured model, and the model that actually answered (response.model) is recorded either way.
 *
 * Never logs the request or response bodies (case content); only status codes and stop reasons.
 */
class AnthropicAnswerGenerator(
    props: AppProperties,
    private val client: AnthropicClient = buildClient(props.llm),
) : AnswerGenerator {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cfg = props.llm

    override val provider = "anthropic"
    override val settings: Map<String, Any?> = mapOf(
        "requestedModel" to cfg.model,
        "effort" to cfg.effort,
        "maxTokens" to cfg.maxTokens,
        "inferenceGeo" to cfg.inferenceGeo.ifBlank { null },
    ).filterValues { it != null }

    override fun available() = true

    override fun generate(request: GenerationRequest): GenerationResult {
        val schema = JsonOutputFormat.Schema.builder()
            .apply { request.outputSchema.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }
            .build()
        val params = MessageCreateParams.builder()
            .model(cfg.model)
            .maxTokens(cfg.maxTokens.toLong())
            .system(request.system)
            .addUserMessage(request.user)
            .thinking(ThinkingConfigAdaptive.builder().build())
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.of(cfg.effort))
                    .format(JsonOutputFormat.builder().schema(schema).build())
                    .build()
            )
            .apply { if (cfg.inferenceGeo.isNotBlank()) inferenceGeo(cfg.inferenceGeo) }
            .build()

        val message = try {
            client.messages().create(params)
        } catch (e: AnthropicServiceException) {
            log.warn("Anthropic API error: status={}", e.statusCode())
            return GenerationResult.Failed("PROVIDER_HTTP_${e.statusCode()}")
        } catch (e: AnthropicException) {
            log.warn("Anthropic API call failed: {}", e.javaClass.simpleName)
            return GenerationResult.Failed("PROVIDER_UNREACHABLE")
        }

        val model = message.model().asString()
        return when (val stop = message.stopReason().map { it.asString() }.orElse(null)) {
            "refusal" -> GenerationResult.Declined(model, message.stopDetails().flatMap { it.category() }.map { it.asString() }.orElse(null))
            "end_turn" -> {
                val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
                if (text.isBlank()) GenerationResult.Failed("EMPTY_OUTPUT")
                else GenerationResult.Completed(text, model, message.usage().inputTokens(), message.usage().outputTokens())
            }
            else -> {
                log.warn("Anthropic response not usable: stopReason={}", stop)
                GenerationResult.Failed(if (stop == "max_tokens") "TRUNCATED" else "STOP_${stop ?: "UNKNOWN"}".uppercase())
            }
        }
    }

    companion object {
        fun buildClient(cfg: AppProperties.Llm): AnthropicClient =
            AnthropicOkHttpClient.builder()
                .apiKey(cfg.anthropic.apiKey)
                .apply { if (cfg.anthropic.baseUrl.isNotBlank()) baseUrl(cfg.anthropic.baseUrl) }
                .timeout(cfg.timeout)
                .maxRetries(cfg.maxRetries)
                .build()
    }
}
