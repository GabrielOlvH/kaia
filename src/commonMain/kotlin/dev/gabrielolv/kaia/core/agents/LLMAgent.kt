package dev.gabrielolv.kaia.core.agents

import dev.gabrielolv.kaia.core.Conversation
import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider
import kotlinx.coroutines.flow.Flow

// 1. Builder specific to LLM Agents
class LLMAgentBuilder : AgentBuilder() { // Inherit common properties
    var provider: LLMProvider? = null
    var systemPrompt: String? = null
    var temperature: Double? = null // Example: Allow overriding temperature
}

// 2. Extension function to create the LLM-specific processor
private fun LLMAgentBuilder.buildProcessor(): (LLMMessage.UserMessage, Conversation) -> Flow<LLMMessage> {
    val llmProvider = requireNotNull(provider) { "LLMProvider must be set for LLMAgent" }
    val prompt = systemPrompt // Capture for the lambda
    val temp = temperature

    return { message, conversation ->
        // Use the history directly from the Conversation object
        val history = conversation.messages

        val options = LLMOptions(
            systemPrompt = prompt,
            temperature = temp ?: 0.7 // Use agent temp or default
        )

        val flow = llmProvider.generate(history + message, options) // Include current message

        flow
    }
}

/**
 * Creates an agent that uses an LLM provider to generate responses.
 * Uses a dedicated builder (`LLMAgentBuilder`) for specific configuration.
 */
fun Agent.Companion.llm(block: LLMAgentBuilder.() -> Unit): Agent {
    val builder = LLMAgentBuilder().apply(block)

    // Set the processor using the extension function
    builder.processor = builder.buildProcessor()

    // Build the agent using the base builder's logic
    return builder.build()
}
