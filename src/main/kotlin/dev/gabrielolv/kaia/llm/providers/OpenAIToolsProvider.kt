package dev.gabrielolv.kaia.llm.providers

import dev.gabrielolv.kaia.core.tools.ToolManager
import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
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
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = true
                isLenient = true
                namingStrategy = JsonNamingStrategy.SnakeCase
            })
        }
    }

    @Serializable
    private data class Message(
        val role: String,
        val content: String? = null,
        val toolCalls: List<ToolCall>? = null,
        val toolCallId: String? = null
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
        val maxTokens: Int? = null
    )

    @Serializable
    private data class Choice(
        val message: Message,
        val finishReason: String
    )

    @Serializable
    private data class Response(
        val choices: List<Choice>
    )

    /**
     * Helper function to make API requests to avoid code duplication
     */
    private suspend fun makeCompletionRequest(request: Request): Response {
        return client.post("$baseUrl/chat/completions") {
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
                        id = toolCall.id,
                        name = toolName,
                        arguments = arguments
                    )
                )

                val result = toolManager.executeTool(toolName, arguments)

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

    /**
     * Generate a flow of messages from the LLM
     */
    override fun generate(prompt: String, options: LLMOptions): Flow<LLMMessage> = channelFlow {
        val messages = mutableListOf<Message>()

        // Add system message if provided
        options.systemPrompt?.let {
            val systemMessage = LLMMessage.SystemMessage(it)
            send(systemMessage)
            messages.add(Message("system", it))
        }

        // Add user message
        val userMessage = LLMMessage.UserMessage(prompt)
        send(userMessage)
        messages.add(Message("user", prompt))

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

        // Build and make the initial request
        val initialRequest = Request(
            model = model,
            messages = messages,
            tools = tools.takeIf { it.isNotEmpty() },
            temperature = options.temperature,
            maxTokens = options.maxTokens
        )

        var currentMessages = messages
        var currentResponse = makeCompletionRequest(initialRequest)
        var currentMessage = currentResponse.choices.firstOrNull()?.message

        // Handle chained tool calls until there are no more tool calls
        val maxIterations = 10 // Safety limit to prevent infinite loops
        var iteration = 0

        while (currentMessage != null && iteration < maxIterations) {
            // Check if the message has tool calls
            if (currentMessage.toolCalls != null && currentMessage.toolCalls!!.isNotEmpty()) {
                // Add the assistant's message with tool calls to the conversation
                currentMessages = (currentMessages + currentMessage).toMutableList()

                // Process tool calls and get tool response messages
                val toolResponses = processToolCalls(currentMessage.toolCalls!!) { message ->
                    send(message)
                }

                // Add tool response messages to the conversation
                currentMessages = (currentMessages + toolResponses).toMutableList()

                // Make a follow-up request with the tool results
                val followUpRequest = Request(
                    model = model,
                    messages = currentMessages,
                    tools = tools.takeIf { it.isNotEmpty() },
                    temperature = options.temperature,
                    maxTokens = options.maxTokens
                )

                currentResponse = makeCompletionRequest(followUpRequest)
                currentMessage = currentResponse.choices.firstOrNull()?.message
                iteration++
            } else {
                // If there are no tool calls, emit the final assistant message and break
                if (currentMessage.content != null) {
                    send(
                        LLMMessage.AssistantMessage(
                            content = currentMessage.content!!,
                            rawResponse = Json.encodeToJsonElement(currentResponse)
                        )
                    )
                }
                break
            }
        }
    }
}