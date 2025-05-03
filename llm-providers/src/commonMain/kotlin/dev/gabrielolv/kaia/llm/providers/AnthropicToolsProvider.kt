package dev.gabrielolv.kaia.llm.providers

import dev.gabrielolv.kaia.core.tools.ToolManager
import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider
import dev.gabrielolv.kaia.utils.httpClient
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Anthropic provider with tool calling capabilities that supports chained tool calls
 * using Flow to emit all messages in the conversation
 */
class AnthropicToolsProvider(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
    private val toolManager: ToolManager
) : LLMProvider {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    @Serializable
    private data class AnthropicMessage(
        val role: String,
        val content: List<AnthropicContent>,
        val id: String? = null
    )

    @Serializable
    private data class AnthropicContent(
        val type: String,
        val text: String? = null,
        val toolUse: AnthropicToolUse? = null,
        val toolResult: AnthropicToolResult? = null
    )

    @Serializable
    private data class AnthropicToolUse(
        val id: String,
        val name: String,
        val input: String
    )

    @Serializable
    private data class AnthropicToolResult(
        val id: String,
        val type: String = "tool_result",
        val output: JsonElement
    )

    @Serializable
    private data class AnthropicTool(
        val name: String,
        val description: String,
        val inputSchema: JsonObject
    )

    @Serializable
    private data class AnthropicRequest(
        val model: String,
        val messages: List<AnthropicMessage>,
        val max_tokens: Int? = null,
        val temperature: Double = 0.7,
        val system: String? = null,
        val tools: List<AnthropicTool>? = null,
        val stop_sequences: List<String>? = null,
        val stream: Boolean = false
    )

    @Serializable
    private data class AnthropicResponse(
        val id: String,
        val type: String,
        val role: String,
        val content: List<AnthropicContent>,
        val model: String,
        val stopReason: String? = null,
        val stopSequence: String? = null,
        val usage: AnthropicUsage? = null
    )

    @Serializable
    private data class AnthropicUsage(
        val inputTokens: Int,
        val outputTokens: Int
    )

    private fun LLMMessage.toAnthropicMessage(): AnthropicMessage? = when (this) {
        is LLMMessage.UserMessage -> AnthropicMessage(
            role = "user",
            content = listOf(AnthropicContent(type = "text", text = content))
        )
        is LLMMessage.AssistantMessage -> AnthropicMessage(
            role = "assistant",
            content = listOf(AnthropicContent(type = "text", text = content))
        )
        is LLMMessage.SystemMessage -> null // System messages are handled separately
        is LLMMessage.ToolCallMessage -> AnthropicMessage(
            role = "assistant",
            content = listOf(
                AnthropicContent(
                    type = "tool_use",
                    toolUse = AnthropicToolUse(
                        id = toolCallId,
                        name = name,
                        input = json.encodeToString(arguments)
                    )
                )
            )
        )
        is LLMMessage.ToolResponseMessage -> AnthropicMessage(
            role = "user",
            content = listOf(
                AnthropicContent(
                    type = "tool_result",
                    toolResult = AnthropicToolResult(
                        id = toolCallId,
                        output = JsonPrimitive(content)
                    )
                )
            )
        )
    }

    /**
     * Helper function to make API requests to avoid code duplication
     */
    private suspend fun makeMessagesRequest(request: AnthropicRequest): AnthropicResponse {
        return httpClient.post("$baseUrl/messages") {
            contentType(ContentType.Application.Json)
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            setBody(json.encodeToString(request))
        }.body()
    }

    /**
     * Process tool calls in parallel and return the response messages
     */
    private suspend fun processToolCalls(
        content: List<AnthropicContent>,
        emitMessage: suspend (LLMMessage) -> Unit
    ) = coroutineScope {
        // Extract all tool use items
        val toolUses = content.mapNotNull { it.toolUse }
        
        // Process each tool use
        val toolResponses = toolUses.map { toolUse ->
            async {
                val toolName = toolUse.name
                val arguments = toolUse.input
                val toolCallId = toolUse.id

                // Emit tool call message
                emitMessage(
                    LLMMessage.ToolCallMessage(
                        toolCallId = toolCallId,
                        name = toolName,
                        arguments =  json.decodeFromString(arguments)
                    )
                )

                val result = toolManager.executeTool(toolCallId, toolName, json.decodeFromString(arguments))

                // Emit tool response message
                val toolResponseMessage = LLMMessage.ToolResponseMessage(
                    toolCallId = toolCallId,
                    content = result.result
                )
                emitMessage(toolResponseMessage)

                // Return the message for the API
                AnthropicMessage(
                    role = "user",
                    content = listOf(
                        AnthropicContent(
                            type = "tool_result",
                            toolResult = AnthropicToolResult(
                                id = toolCallId,
                                output = JsonPrimitive(result.result)
                            )
                        )
                    )
                )
            }
        }

        // Wait for all tool calls to complete and return the results
        toolResponses.map { it.await() }
    }

    override fun generate(
        messages: List<LLMMessage>,
        options: LLMOptions
    ): Flow<LLMMessage> = channelFlow {
        // Prepare messages for the API call
        val apiMessages = mutableListOf<AnthropicMessage>()

        // Convert and add conversation messages
        val conversationMessages = messages.filterNot { it is LLMMessage.SystemMessage }
        conversationMessages.mapNotNull { it.toAnthropicMessage() }.forEach { apiMessages.add(it) }

        // Ensure there's at least one message
        if (apiMessages.isEmpty()) {
            send(LLMMessage.SystemMessage("Error: No valid messages found to send to Anthropic API after filtering."))
            return@channelFlow
        }

        // Extract system message
        val systemPrompt = options.systemPrompt ?: 
            messages.filterIsInstance<LLMMessage.SystemMessage>().lastOrNull()?.content

        // Convert registered tools to Anthropic format
        val tools = toolManager.getAllTools().takeIf { it.isNotEmpty() }?.map { tool ->
            AnthropicTool(
                name = tool.name,
                description = tool.description,
                inputSchema = tool.parameterSchema
            )
        }

        // --- Tool call loop ---
        var currentMessages = apiMessages.toList()
        val maxIterations = 10
        var iteration = 0

        while (iteration < maxIterations) {
            val request = AnthropicRequest(
                model = model,
                messages = currentMessages,
                max_tokens = options.maxTokens ?: 4096,
                temperature = options.temperature,
                system = systemPrompt,
                tools = tools,
                stop_sequences = options.stopSequences.takeIf { it.isNotEmpty() }
            )

            val response: AnthropicResponse = try {
                makeMessagesRequest(request)
            } catch (e: Exception) {
                send(LLMMessage.SystemMessage("Error calling Anthropic API: ${e.message}"))
                return@channelFlow
            }

            // Check if there are tool use items in the response
            val hasToolUses = response.content.any { it.type == "tool_use" }

            if (hasToolUses) {
                // Add the assistant's message with tool uses to our history
                val assistantMessage = AnthropicMessage(
                    role = "assistant",
                    content = response.content,
                    id = response.id
                )
                currentMessages = currentMessages + assistantMessage

                // Process tool calls and get responses
                val toolResponseMessages = processToolCalls(response.content) { message ->
                    send(message) // Emit tool call and response messages
                }

                // Add tool responses to conversation history
                currentMessages = currentMessages + toolResponseMessages
                iteration++
            } else {
                // No tool calls, this is the final assistant message
                val textContent = response.content.firstOrNull { it.type == "text" }?.text
                if (textContent != null) {
                    val finalAssistantMessage = LLMMessage.AssistantMessage(
                        content = textContent,
                        rawResponse = Json.encodeToJsonElement(response)
                    )
                    send(finalAssistantMessage)
                } else {
                    send(LLMMessage.SystemMessage("Warning: Anthropic response contained no text content."))
                }
                break // Exit loop
            }
        }

        if (iteration == maxIterations) {
            send(LLMMessage.SystemMessage("Error: Reached maximum tool call iterations ($maxIterations)."))
        }
    }
}
