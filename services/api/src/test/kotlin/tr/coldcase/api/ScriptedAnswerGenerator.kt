package tr.coldcase.api

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tr.coldcase.api.qa.AnswerGenerator
import tr.coldcase.api.qa.GenerationRequest
import tr.coldcase.api.qa.GenerationResult

/**
 * Test double for the LLM. By default it answers with one claim quoting the first passage verbatim; tests
 * replace [script] to simulate hallucinated passages, fabricated quotes, refusals, failures and injections.
 */
class ScriptedAnswerGenerator : AnswerGenerator {
    private val mapper = JsonMapper.builder().build()

    @Volatile var enabled = true
    @Volatile var script: (GenerationRequest) -> GenerationResult = ::citeFirstPassage
    @Volatile var calls = 0
    @Volatile var lastRequest: GenerationRequest? = null

    override val provider = "test"
    override val settings: Map<String, Any?> = mapOf("requestedModel" to "test/scripted")
    override fun available() = enabled

    override fun generate(request: GenerationRequest): GenerationResult {
        calls++
        lastRequest = request
        return script(request)
    }

    fun reset() {
        enabled = true
        script = ::citeFirstPassage
        calls = 0
        lastRequest = null
    }

    /** The passages exactly as the model would see them. */
    fun passages(request: GenerationRequest): List<JsonNode> {
        val json = request.user.substringAfter("<passages>\n").substringBefore("\n</passages>")
        return mapper.readTree(json).values().toList()
    }

    fun answer(vararg claims: Map<String, Any?>, outcome: String = "ANSWERED", limitations: List<String> = emptyList(),
               conflicts: List<Map<String, Any?>> = emptyList(), suggestedLead: String? = null): GenerationResult =
        GenerationResult.Completed(
            mapper.writeValueAsString(mapOf("outcome" to outcome, "claims" to claims.toList(), "limitations" to limitations,
                "conflicts" to conflicts, "suggestedLead" to suggestedLead)),
            "test/scripted-1", 100, 50,
        )

    fun claim(text: String, kind: String, speaker: String?, vararg citations: Pair<String, String>) = mapOf(
        "text" to text, "kind" to kind, "speaker" to speaker,
        "citations" to citations.map { mapOf("passageId" to it.first, "quote" to it.second) },
    )

    private fun citeFirstPassage(request: GenerationRequest): GenerationResult {
        val p = passages(request).first()
        val quote = p["text"].asText().split(Regex("\\s+")).take(4).joinToString(" ")
        return answer(claim("Kaynak bu konuda bilgi veriyor.", "DOCUMENTED_FACT", null, p["id"].asText() to quote))
    }
}
