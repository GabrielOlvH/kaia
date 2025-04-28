package dev.gabrielolv.kaia.llm

import kotlinx.serialization.json.JsonElement

/**
 * Represents a message in the LLM conversation flow
 */
sealed class LLMMessage {
    /**
     * User input message
     */
    data class UserMessage(val content: String) : LLMMessage()

    /**
     * System instruction message
     */
    data class SystemMessage(val content: String) : LLMMessage()

    /**
     * Assistant response message
     */
    data class AssistantMessage(val content: String, val rawResponse: JsonElement? = null) : LLMMessage()

    /**
     * Tool call made by the assistant
     */
    data class ToolCallMessage(
        val id: String,
        val name: String,
        val arguments: JsonElement
    ) : LLMMessage()

    /**
     * Tool response message
     */
    data class ToolResponseMessage(
        val toolCallId: String,
        val content: String
    ) : LLMMessage()
}

data class LLMResponse(
    val content: String,
    val rawResponse: JsonElement? = null
)
