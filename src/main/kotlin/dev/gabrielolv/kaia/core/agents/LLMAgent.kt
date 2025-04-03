package dev.gabrielolv.kaia.core.agents

import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider

/**
 * Creates an agent that uses an LLM provider to generate responses
 */
fun Agent.Companion.llm(
    provider: LLMProvider,
    systemPrompt: String? = null,
    // Allow configuring history size per agent
    block: AgentBuilder.() -> Unit
): Agent {
    val builder = AgentBuilder().apply(block)

    builder.processor = processor@{ message, conversation -> // Updated signature

        val options = LLMOptions(
            systemPrompt = systemPrompt,
            temperature = 0.7
        )

        val history = conversation.messages.toList()

        return@processor provider.generate(history, options)
    }

    return builder.build()
}