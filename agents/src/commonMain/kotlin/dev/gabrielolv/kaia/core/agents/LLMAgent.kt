package dev.gabrielolv.kaia.core.agents

import dev.gabrielolv.kaia.core.Conversation
import dev.gabrielolv.kaia.core.model.*
import dev.gabrielolv.kaia.core.tools.ToolCall
import dev.gabrielolv.kaia.core.tools.ToolResult
import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

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
    var recursionLimit: Int? = null
}

private fun LLMAgentBuilder.buildProcessor(): (LLMMessage.UserMessage, Conversation) -> Flow<AgentResult> {
    val llmProvider = requireNotNull(provider) { "LLMProvider must be set for LLMAgent" }
    val prompt = systemPrompt
    val temp = temperature
    val recursion = recursionLimit

    return { message, conversation ->
        val history = conversation.messages

        val options = LLMOptions(
            systemPrompt = prompt,
            temperature = temp ?: 0.7,
            toolRecursionLimit = recursion ?: 10
        )

        llmProvider.generate(history + message, options)
            .map { llmMessage ->
                when (llmMessage) {
                    is LLMMessage.AssistantMessage -> TextResult(content = llmMessage.content, rawMessage = llmMessage)
                    is LLMMessage.SystemMessage -> SystemResult(message = llmMessage.content, rawMessage = llmMessage)
                    is LLMMessage.ToolCallMessage -> ToolCallResult(
                        toolCalls = listOf(ToolCall(id = llmMessage.toolCallId, name = llmMessage.name, arguments = Json.encodeToString(llmMessage.arguments)) ), rawMessage = llmMessage
                    )

                    is LLMMessage.ToolResponseMessage -> ToolResponseResult(
                        toolResults = listOf(
                            ToolResult(
                                llmMessage.toolCallId,
                                llmMessage.content
                            )
                        ), rawMessage = llmMessage
                    )

                    else -> ErrorResult(
                        error = null,
                        message = "Unexpected message type: ${llmMessage::class.simpleName}",
                        rawMessage = llmMessage
                    )
                }
            }
            .catch { e ->
                emit(ErrorResult(message = "LLM generation failed: ${e.message}", error = e))
            }
    }
}
