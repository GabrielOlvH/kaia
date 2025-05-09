package dev.gabrielolv.kaia.core.model

import dev.gabrielolv.kaia.core.tools.ToolCall
import dev.gabrielolv.kaia.core.tools.ToolResult
import dev.gabrielolv.kaia.llm.LLMMessage

/**
 * Represents the outcome of a single step of an agent's execution.
 * This allows for handling different types of agent outputs in a structured way.
 */
sealed interface AgentResult {
    /** The raw LLM message associated with this result, if applicable. */
    val rawMessage: LLMMessage?
}

/**
 * Represents a textual response from an agent, intended for the user or another agent.
 */
data class TextResult(
    val content: String,
    override val rawMessage: LLMMessage.AssistantMessage? = null
) : AgentResult

/**
 * Represents structured data output from an agent.
 * The actual data type is generic.
 *
 * @param T The specific Kotlin type of the structured data (e.g., DirectorOutput).
 * @param data The parsed, typed data object.
 * @param rawContent The original raw content (e.g., JSON string) from which data was parsed.
 */
data class StructuredResult<T : Any>(
    val data: T,
    val rawContent: String?,
    override val rawMessage: LLMMessage.AssistantMessage? = null
) : AgentResult

/**
 * Represents a request from an agent to execute one or more tools.
 */
data class ToolCallResult(
    val toolCalls: List<ToolCall>,
    override val rawMessage: LLMMessage.ToolCallMessage? = null
) : AgentResult

/**
 * Represents the results obtained after executing tools.
 * This might be used internally by the agent or orchestration logic.
 */
data class ToolResponseResult(
    val toolResults: List<ToolResult>,
    override val rawMessage: LLMMessage.ToolResponseMessage? = null
) : AgentResult

/**
 * Represents an error encountered during agent execution.
 */
data class ErrorResult(
    val error: Throwable?,
    val message: String = error?.message ?: "Agent execution failed",
    override val rawMessage: LLMMessage? = null
) : AgentResult

/**
 * Represents a system-level message or status update during agent processing.
 */
data class SystemResult(
    val message: String,
    override val rawMessage: LLMMessage.SystemMessage? = null
) : AgentResult

