package dev.gabrielolv.kaia.core.agents

import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider

/**
 * Creates an agent that uses an LLM provider to generate responses
 */
fun Agent.Companion.llm(
    provider: LLMProvider,
    systemPrompt: String? = null,
    block: AgentBuilder.() -> Unit
): Agent {
    val builder = AgentBuilder().apply(block)

    // Set up the flow-based processor
    builder.processor = processor@{ message ->

        val options = LLMOptions(
            systemPrompt = systemPrompt,
            temperature = 0.7
        )

        return@processor provider.generate(message.content, options)

    }

    return builder.build()
}