package dev.gabrielolv.kaia.llm.providers

import dev.gabrielolv.kaia.core.tools.ToolManager
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider
import dev.gabrielolv.kaia.llm.LLMResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * LLM provider with tool calling capabilities
 */
class ToolCallingProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-4-turbo",
    private val toolManager: ToolManager? = null
) : LLMProvider {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = true
                isLenient = true
            })
        }
    }

    @Serializable
    private data class Message(
        val role: String,
        val content: String? = null,
        val tool_calls: List<ToolCall>? = null,
        val tool_call_id: String? = null
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
        val max_tokens: Int? = null
    )

    @Serializable
    private data class Choice(
        val message: Message,
        val finish_reason: String
    )

    @Serializable
    private data class Response(
        val choices: List<Choice>
    )

    override suspend fun generate(prompt: String, options: LLMOptions): LLMResponse = coroutineScope {
        val messages = mutableListOf<Message>()

        options.systemPrompt?.let {
            messages.add(Message("system", it))
        }

        messages.add(Message("user", prompt))

        // Convert registered tools to OpenAI format
        val tools = toolManager?.getAllTools()?.map { tool ->
            ToolDefinition(
                function = FunctionDefinition(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.parameterSchema
                )
            )
        }

        val request = Request(
            model = model,
            messages = messages,
            tools = tools?.takeIf { it.isNotEmpty() },
            temperature = options.temperature,
            max_tokens = options.maxTokens
        )

        // Make the initial request
        val response: Response = client.post("$baseUrl/chat/completions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(request)
        }.body()

        val message = response.choices.firstOrNull()?.message

        // If the model wants to call tools, handle those calls
        val toolCalls = message?.tool_calls
        if (!toolCalls.isNullOrEmpty()) {
            assert(toolManager != null) { "Received tool call but toolManager is null!" }
            toolManager!!
            // Execute tool calls in parallel
            val toolResults = toolCalls.map { toolCall ->
                async {
                    val functionCall = toolCall.function
                    val toolName = functionCall.name
                    val arguments = Json.parseToJsonElement(functionCall.arguments).jsonObject

                    val result = toolManager.executeTool(toolName, arguments)

                    // Create tool response message
                    Message(
                        role = "tool",
                        content = result.result,
                        tool_call_id = toolCall.id
                    )
                }
            }.map { it.await() }

            // Add the assistant's message with tool calls
            val updatedMessages = messages + message

            // Add tool response messages
            val finalMessages = updatedMessages + toolResults

            // Make a follow-up request with the tool results
            val followUpRequest = Request(
                model = model,
                messages = finalMessages,
                tools = tools!!.takeIf { it.isNotEmpty() },
                temperature = options.temperature,
                max_tokens = options.maxTokens
            )

            val followUpResponse: Response = client.post("$baseUrl/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(followUpRequest)
            }.body()

            val finalMessage = followUpResponse.choices.firstOrNull()?.message
            return@coroutineScope LLMResponse(
                content = finalMessage?.content ?: "",
                rawResponse = Json.encodeToJsonElement(followUpResponse)
            )
        }

        // If no tool calls, just return the content
        return@coroutineScope LLMResponse(
            content = message?.content ?: "",
            rawResponse = Json.encodeToJsonElement(response)
        )
    }
}