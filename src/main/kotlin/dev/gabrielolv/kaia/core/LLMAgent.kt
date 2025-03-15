package dev.gabrielolv.kaia.core

import dev.gabrielolv.kaia.core.tools.ToolCallingProvider
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider

fun Agent.Companion.llm(
    provider: LLMProvider,
    systemPrompt: String? = null,
    block: AgentBuilder.() -> Unit
): Agent {
    val builder = AgentBuilder().apply(block)
    builder.processor = processor@{ message ->
        val options = LLMOptions(
            systemPrompt = systemPrompt,
            temperature = 0.7
        )

        val llmResponse = provider.generate(message.content, options)

        Message(
            sender = builder.id.takeIf { it.isNotEmpty() } ?: "llm-agent",
            recipient = message.sender,
            content = llmResponse.content
        )
    }

    return builder.build()
}

fun Agent.Companion.withTools(
    provider: ToolCallingProvider,
    systemPrompt: String? = null,
    block: AgentBuilder.() -> Unit
): Agent {
    val builder = AgentBuilder().apply(block)

    // Set the tool-enabled processor
    builder.processor = { message ->
        val options = LLMOptions(
            systemPrompt = systemPrompt,
            temperature = 0.7
        )

        val llmResponse = provider.generate(message.content, options)

        Message(
            sender = builder.id.takeIf { it.isNotEmpty() } ?: "tool-agent",
            recipient = message.sender,
            content = llmResponse.content
        )
    }

    return builder.build()
}