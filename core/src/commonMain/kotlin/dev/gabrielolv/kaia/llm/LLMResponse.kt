package dev.gabrielolv.kaia.llm

import dev.gabrielolv.kaia.utils.nextMessageId
import kotlinx.serialization.json.JsonElement

/**
 * Represents a message in the LLM conversation flow
 */
sealed class LLMMessage(val messageId: String = nextMessageId) {
    /**
     * Returns a string representation suitable for including in a prompt.
     */
    abstract fun asPromptString(): String

    /**
     * User input message
     */
    data class UserMessage(val content: String) : LLMMessage() {
        override fun asPromptString(): String = "user: $content"
    }

    /**
     * System instruction message
     */
    data class SystemMessage(val content: String) : LLMMessage() {
        override fun asPromptString(): String = "system: $content"
    }

    /**
     * Assistant response message
     */
    data class AssistantMessage(val content: String, val rawResponse: JsonElement? = null) : LLMMessage() {
        override fun asPromptString(): String = "assistant: $content"
    }

    /**
     * Tool call made by the assistant
     */
    data class ToolCallMessage(
        val toolCallId: String,
        val name: String,
        val arguments: JsonElement
    ) : LLMMessage() {
        override fun asPromptString(): String = "tool_call: id=$toolCallId name=$name args=$arguments"
    }

    /**
     * Tool response message
     */
    data class ToolResponseMessage(
        val toolCallId: String,
        val content: String
    ) : LLMMessage() {
        override fun asPromptString(): String = "tool_response: id=$toolCallId content=$content"
    }
}

data class LLMResponse(
    val content: String,
    val rawResponse: JsonElement? = null
)
