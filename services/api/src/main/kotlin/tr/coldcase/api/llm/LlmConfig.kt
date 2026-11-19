package tr.coldcase.api.llm

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tr.coldcase.api.config.AppProperties
import tr.coldcase.api.qa.AnswerGenerator
import tr.coldcase.api.qa.NoAnswerGenerator

@Configuration
class LlmConfig {
    /** Selects the answer generator. Default none: no case content leaves our infrastructure (ADR 0002). */
    @Bean
    fun answerGenerator(props: AppProperties): AnswerGenerator = when (props.llm.provider) {
        "none" -> NoAnswerGenerator()
        "anthropic" -> {
            require(props.llm.anthropic.apiKey.isNotBlank()) { "app.llm.provider=anthropic needs ANTHROPIC_API_KEY" }
            AnthropicAnswerGenerator(props)
        }
        else -> error("Unknown app.llm.provider '${props.llm.provider}' (allowed: none, anthropic)")
    }
}
