package tr.coldcase.api

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tr.coldcase.api.config.AppProperties
import tr.coldcase.api.llm.AnthropicAnswerGenerator
import tr.coldcase.api.qa.GenerationRequest
import tr.coldcase.api.qa.GenerationResult
import tr.coldcase.api.qa.QaPrompt
import java.net.InetSocketAddress
import java.time.Duration

/** The Claude adapter against a local stand-in for the Messages API: request shape and stop-reason handling. No network. */
class AnthropicAnswerGeneratorTest {
    private val mapper = JsonMapper.builder().build()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    @Volatile private var reply: Pair<Int, String> = 200 to ""
    @Volatile private var received: JsonNode? = null
    @Volatile private var apiKeyHeader: String? = null

    init {
        server.createContext("/v1/messages") { ex ->
            received = mapper.readTree(ex.requestBody.readAllBytes())
            apiKeyHeader = ex.requestHeaders.getFirst("x-api-key") ?: ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
            val bytes = reply.second.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(reply.first, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private val generator = AnthropicAnswerGenerator(
        AppProperties(
            llm = AppProperties.Llm(
                provider = "anthropic", model = "claude-opus-5-5", effort = "high", maxTokens = 16000,
                timeout = Duration.ofSeconds(10), maxRetries = 0,
                anthropic = AppProperties.Anthropic(apiKey = "test-key", baseUrl = "http://127.0.0.1:${server.address.port}"),
            ),
        ),
    )

    private val request = GenerationRequest(QaPrompt.SYSTEM, "<question>\"Soru?\"</question>", QaPrompt.SCHEMA)

    private fun message(stopReason: String, text: String = "", extra: String = "") = """
        {"id":"msg_test","type":"message","role":"assistant","model":"claude-opus-5-5",
         "content":[{"type":"text","text":${mapper.writeValueAsString(text)}}],
         "stop_reason":"$stopReason","stop_sequence":null$extra,
         "usage":{"input_tokens":1200,"output_tokens":340}}
    """.trimIndent()

    @Test
    fun `sends a structured-output request with adaptive thinking and high effort`() {
        reply = 200 to message("end_turn", """{"outcome":"INSUFFICIENT_EVIDENCE","claims":[],"limitations":[],"conflicts":[],"suggestedLead":null}""")
        val result = generator.generate(request)

        assertThat(result).isInstanceOf(GenerationResult.Completed::class.java)
        result as GenerationResult.Completed
        assertThat(result.model).isEqualTo("claude-opus-5-5")
        assertThat(result.inputTokens).isEqualTo(1200)
        assertThat(result.outputJson).contains("INSUFFICIENT_EVIDENCE")

        val body = received!!
        assertThat(apiKeyHeader).isEqualTo("test-key") // x-api-key or Authorization: Bearer, depending on SDK version
        assertThat(body["model"].asText()).isEqualTo("claude-opus-5-5")
        assertThat(body["max_tokens"].asInt()).isEqualTo(16000)
        assertThat(body["system"].asText()).isEqualTo(QaPrompt.SYSTEM)
        assertThat(body["thinking"]["type"].asText()).isEqualTo("adaptive")
        assertThat(body["output_config"]["effort"].asText()).isEqualTo("high")
        assertThat(body["output_config"]["format"]["type"].asText()).isEqualTo("json_schema")
        assertThat(body["output_config"]["format"]["schema"]["additionalProperties"].asBoolean()).isFalse()
        assertThat(body["output_config"]["format"]["schema"]["required"].values().map { it.asText() }).contains("outcome", "claims")
        assertThat(body["messages"][0]["role"].asText()).isEqualTo("user")
        assertThat(body.has("inference_geo")).isFalse()
    }

    @Test
    fun `a refusal is reported as declined with its category`() {
        reply = 200 to message("refusal", extra = ""","stop_details":{"type":"refusal","category":"cyber","explanation":null}""")
        val result = generator.generate(request)
        assertThat(result).isEqualTo(GenerationResult.Declined("claude-opus-5-5", "cyber"))
    }

    @Test
    fun `truncated output and API errors are failures`() {
        reply = 200 to message("max_tokens", "{\"outcome\":\"ANSW")
        assertThat(generator.generate(request)).isEqualTo(GenerationResult.Failed("TRUNCATED"))
        reply = 529 to """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""
        assertThat(generator.generate(request)).isEqualTo(GenerationResult.Failed("PROVIDER_HTTP_529"))
    }
}
