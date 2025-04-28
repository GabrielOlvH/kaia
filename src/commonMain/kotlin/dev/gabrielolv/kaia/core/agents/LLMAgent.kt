package dev.gabrielolv.kaia.core.agents

import dev.gabrielolv.kaia.core.Conversation
import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider
import kotlinx.coroutines.flow.Flow

/**
 * Creates an agent that uses an LLM provider to generate responses.
 * Uses a dedicated builder (`LLMAgentBuilder`) for specific configuration.
 */
fun Agent.Companion.llm(block: LLMAgentBuilder.() -> Unit): Agent {
    val builder = LLMAgentBuilder().apply(block)
    builder.processor = builder.buildProcessor()
    return builder.build()
}


class LLMAgentBuilder : AgentBuilder() {
    var provider: LLMProvider? = null
    var systemPrompt: String? = null
    var temperature: Double? = null
}

private fun LLMAgentBuilder.buildProcessor(): (LLMMessage.UserMessage, Conversation) -> Flow<LLMMessage> {
    val llmProvider = requireNotNull(provider) { "LLMProvider must be set for LLMAgent" }
    val prompt = systemPrompt
    val temp = temperature

    return { message, conversation ->
        val history = conversation.messages

        val options = LLMOptions(
            systemPrompt = prompt,
            temperature = temp ?: 0.7
        )

        val flow = llmProvider.generate(history + message, options)

        flow
    }
}

