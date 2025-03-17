package dev.gabrielolv.kaia.core.agents

import dev.gabrielolv.kaia.core.Message
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