package dev.gabrielolv.kaia.llm.providers

import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider
import dev.gabrielolv.kaia.llm.providers.OpenAIToolsProvider.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.encodeToJsonElement

internal class OpenAIProvider(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String
) : LLMProvider {
    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    @Serializable
    private data class OpenAIMessage(
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
    private data class OpenAIRequest(
        val model: String,
        val messages: List<OpenAIMessage>,
        val temperature: Double = 0.7,
        val maxTokens: Int? = null,
        val stop: List<String>? = null,
        val responseFormat: ResponseFormat = ResponseFormat()
    )

    @Serializable
    private data class ResponseFormat(val type: String = "text")

    @Serializable
    private data class OpenAIChoice(
        val message: OpenAIMessage
    )

    @Serializable
    private data class OpenAIResponse(
        val choices: List<OpenAIChoice>
    )

    private fun LLMMessage.toOpenAIMessage(): OpenAIMessage = when (this) {
        is LLMMessage.UserMessage -> OpenAIMessage("user", content = content)
        is LLMMessage.AssistantMessage -> OpenAIMessage("assistant", content = content)
        is LLMMessage.SystemMessage -> OpenAIMessage("assistant", content = content)
        is LLMMessage.ToolCallMessage -> {
            OpenAIMessage(
                role = "assistant",
                toolCalls = listOf(
                    ToolCall(
                        id = id,
                        type = "function",
                        function = FunctionCall(name = name, arguments = json.encodeToString(arguments))
                    )
                )
            )
        }

        is LLMMessage.ToolResponseMessage -> OpenAIMessage(
            role = "tool",
            toolCallId = toolCallId,
            content = content
        )
    }


    override fun generate(
        messages: List<LLMMessage>, // Changed parameter
        options: LLMOptions
    ): Flow<LLMMessage> = flow {
        // Prepare messages for the API call, applying history limits
        val apiMessages = mutableListOf<OpenAIMessage>()
        var systemMessage: OpenAIMessage? = null

        // Extract system message first if it exists in the history
        // Or use the one from options if provided (options override history)
        val systemPromptFromOptions = options.systemPrompt?.let { OpenAIMessage("system", it) }
        val systemPromptFromHistory = messages.filterIsInstance<LLMMessage.SystemMessage>().lastOrNull()?.toOpenAIMessage()

        systemMessage = systemPromptFromOptions ?: systemPromptFromHistory

        systemMessage?.let { apiMessages.add(it) }

        // Get the relevant conversation history (excluding system messages already handled)
        val conversationMessages = messages

        // Apply history size limit
        val trimmedConversation = options.historySize?.let { size ->
            conversationMessages.takeLast(size)
        } ?: conversationMessages

        // Convert and add conversation messages
        trimmedConversation.map { it.toOpenAIMessage() }.forEach { apiMessages.add(it) }

        // Ensure there's at least one non-system message if the original list had them
        if (apiMessages.none { it.role != "system" } && conversationMessages.isNotEmpty()) {
            // This might happen if all messages were filtered out (e.g., only tool messages)
            // Add the very last message regardless of type if needed, or handle error
            messages.lastOrNull()?.toOpenAIMessage()?.let { apiMessages.add(it) }
        }

        if (apiMessages.none { it.role != "system" }) {
            emit(LLMMessage.SystemMessage("Error: No valid messages found to send to LLM after filtering."))
            return@flow
        }


        val request = OpenAIRequest(
            model = model,
            messages = apiMessages, // Use the prepared list
            temperature = options.temperature,
            maxTokens = options.maxTokens,
            stop = options.stopSequences.takeIf { it.isNotEmpty() },
            responseFormat = ResponseFormat(options.responseFormat)
        )

        val response: OpenAIResponse = try {
            client.post("$baseUrl/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(request)
            }.body()
        } catch (e: Exception) {
            // Handle API errors gracefully
            emit(LLMMessage.SystemMessage("Error calling OpenAI API: ${e.message}"))
            return@flow
        }


        val content = response.choices.firstOrNull()?.message?.content ?: ""
        val rawResponse = Json.encodeToJsonElement(response)

        // Emit only the assistant message generated by the API
        emit(LLMMessage.AssistantMessage(content, rawResponse))
    }

}