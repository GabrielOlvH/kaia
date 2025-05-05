package dev.gabrielolv.kaia.llm.providers

import dev.gabrielolv.kaia.core.tools.ToolManager
import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider
import dev.gabrielolv.kaia.utils.createHttpEngine
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * LLM provider with tool calling capabilities that supports chained tool calls
 * using Flow to emit all messages in the conversation
 */
class OpenAIToolsProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-4-turbo",
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
    val httpClient = HttpClient(createHttpEngine()) {
        install(ContentNegotiation) {
            json(json)
        }
        expectSuccess = true
    }

    @Serializable
    private data class Message(
        val role: String,
        val content: String? = null,
        val toolCalls: List<ToolCall>? = null,
        val toolCallId: String? = null,
        val name: String? = null
    )

    @Serializable
    private data class ToolCall(
        val id: String,
        val type: String = "function",
        val function: FunctionCall
    )

    @Serializable
    private data class FunctionCall(
        val name: String,
        val arguments: String
    )

    @Serializable
    private data class ToolDefinition(
        val type: String = "function",
        val function: FunctionDefinition
    )

    @Serializable
    private data class FunctionDefinition(
        val name: String,
        val description: String,
        val parameters: JsonObject
    )

    @Serializable
    private data class Request(
        val model: String,
        val messages: List<Message>,
        val tools: List<ToolDefinition>? = null,
        val temperature: Double = 0.7,
        val maxTokens: Int? = null,
        val responseFormat: ResponseFormat = ResponseFormat()
    )

    @Serializable
    private data class ResponseFormat(val type: String = "text")

    @Serializable
    private data class Choice(
        val message: Message,
        val finishReason: String
    )

    @Serializable
    private data class Response(
        val choices: List<Choice>
    )

    private fun LLMMessage.toOpenAIToolMessage(): Message? = when (this) {
        is LLMMessage.UserMessage -> Message("user", content = content)
        is LLMMessage.AssistantMessage -> Message("assistant", content = content)
        is LLMMessage.SystemMessage -> null
        is LLMMessage.ToolCallMessage -> {
            Message(
                role = "assistant",
                toolCalls = listOf(
                    ToolCall(
                        id = toolCallId,
                        type = "function",
                        function = FunctionCall(name = name, arguments = json.encodeToString(arguments))
                    )
                )
            )
        }

        is LLMMessage.ToolResponseMessage -> Message(
            role = "tool",
            toolCallId = toolCallId,
            content = content
        )
    }

    /**
     * Helper function to make API requests to avoid code duplication
     */
    private suspend fun makeCompletionRequest(request: Request): Response {
        return httpClient.post("$baseUrl/chat/completions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(request)
        }.body()
    }

    /**
     * Process tool calls in parallel and return the response messages
     */
    private suspend fun processToolCalls(
        toolCalls: List<ToolCall>,
        emitMessage: suspend (LLMMessage) -> Unit
    ) = coroutineScope {
        toolCalls.map { toolCall ->
            async {
                val functionCall = toolCall.function
                val toolName = functionCall.name
                val arguments = Json.parseToJsonElement(functionCall.arguments).jsonObject

                // Emit tool call message
                emitMessage(
                    LLMMessage.ToolCallMessage(
                        toolCallId = toolCall.id,
                        name = toolName,
                        arguments = arguments
                    )
                )

                val result = toolManager.executeTool(toolCall.id, toolName, arguments)

                // Emit tool response message
                val toolResponseMessage = LLMMessage.ToolResponseMessage(
                    toolCallId = toolCall.id,
                    content = result.result
                )
                emitMessage(toolResponseMessage)

                // Return the message for the API
                Message(
                    role = "tool",
                    content = result.result,
                    toolCallId = toolCall.id
                )
            }
        }.map { it.await() }
    }

    override fun generate(
        messages: List<LLMMessage>, // Changed parameter
        options: LLMOptions
    ): Flow<LLMMessage> = channelFlow {
        // Prepare initial messages for the API call, applying history limits
        val apiMessages = mutableListOf(Message("system", options.systemPrompt))

        // Get conversation history (excluding system messages)
        val conversationMessages = messages


        // Convert and add conversation messages
        conversationMessages.mapNotNull { it.toOpenAIToolMessage() }.forEach { apiMessages.add(it) }

        // Ensure there's content if needed
        if (apiMessages.none { it.role != "system" }) {
            send(LLMMessage.SystemMessage("Error: No valid messages found to send to LLM after filtering."))
            close() // Close the channelFlow
            return@channelFlow
        }

        // Convert registered tools to OpenAI format
        val tools = toolManager.getAllTools().map { tool ->
            ToolDefinition(
                function = FunctionDefinition(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.parameterSchema
                )
            )
        }

        // --- Tool call loop ---
        var currentApiMessages = apiMessages.toList() // Start with the history
        val maxIterations = options.toolRecursionLimit
        var iteration = 0

        while (iteration < maxIterations) {
            val request = Request(
                model = model,
                messages = currentApiMessages, // Use current message list
                tools = tools.takeIf { it.isNotEmpty() },
                temperature = options.temperature,
                maxTokens = options.maxTokens,
                responseFormat = ResponseFormat(options.responseFormat) // Pass format if needed
            )

            val response: Response = try {
                makeCompletionRequest(request)
            } catch (e: Exception) {
                send(LLMMessage.SystemMessage("Error during API call: ${e.message}"))
                close(e) // Close channel with error
                return@channelFlow
            }

            val choice = response.choices.firstOrNull()
            val responseMessage = choice?.message

            if (responseMessage == null) {
                send(LLMMessage.SystemMessage("Error: No response message from LLM."))
                break // Exit loop
            }

            // Check for tool calls in the response
            if (!responseMessage.toolCalls.isNullOrEmpty()) {
                // Add the assistant's message *requesting* the tool call to our history
                // The API response 'responseMessage' already has the 'assistant' role and tool_calls
                currentApiMessages = currentApiMessages + responseMessage

                // Process tool calls (this emits ToolCallMessage and ToolResponseMessage via 'send')
                val toolResponseMessages = processToolCalls(responseMessage.toolCalls) { message ->
                    send(message) // Emit ToolCall and ToolResponse messages downstream
                }

                // Add the tool *responses* to the history for the next API call
                currentApiMessages = currentApiMessages + toolResponseMessages
                iteration++

            } else {
                // No tool calls, this is the final assistant message
                if (responseMessage.content != null) {
                    val finalAssistantMessage = LLMMessage.AssistantMessage(
                        content = responseMessage.content,
                        rawResponse = Json.encodeToJsonElement(response)
                    )
                    send(finalAssistantMessage) // Emit final response
                } else {
                    // Handle cases where there's no content and no tool calls (should be rare)
                    send(LLMMessage.SystemMessage("Warning: LLM finished without content or tool calls."))
                }
                break // Exit loop
            }
        }

        if (iteration == maxIterations) {
            send(LLMMessage.SystemMessage("Error: Reached maximum tool call iterations ($maxIterations)."))
        }
    }
}