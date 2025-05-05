package dev.gabrielolv.kaia.llm.providers

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.encodeToJsonElement

internal class GeminiProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val model: String = "gemini-1.5-flash"
) : LLMProvider {
    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
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
    private data class GeminiContent(
        val role: String? = null,
        val parts: List<GeminiPart>
    )

    @Serializable
    private data class GeminiPart(
        val text: String? = null
    )

    @Serializable
    private data class GeminiRequest(
        val contents: List<GeminiContent>,
        val generationConfig: GeminiGenerationConfig? = null,
        val safetySettings: List<GeminiSafetySetting>? = null
    )

    @Serializable
    private data class GeminiGenerationConfig(
        val temperature: Double? = null,
        val maxOutputTokens: Int? = null,
        val topP: Double? = null,
        val topK: Int? = null,
        val stopSequences: List<String>? = null
    )

    @Serializable
    private data class GeminiSafetySetting(
        val category: String,
        val threshold: String
    )

    @Serializable
    private data class GeminiResponse(
        val candidates: List<GeminiCandidate>? = null,
        val promptFeedback: GeminiPromptFeedback? = null,
        val error: GeminiError? = null
    )

    @Serializable
    private data class GeminiError(
        val code: Int? = null,
        val message: String? = null,
        val status: String? = null
    )

    @Serializable
    private data class GeminiCandidate(
        val content: GeminiContent? = null,
        val finishReason: String? = null,
        val safetyRatings: List<GeminiSafetyRating>? = null
    )

    @Serializable
    private data class GeminiPromptFeedback(
        val safetyRatings: List<GeminiSafetyRating>? = null,
        val blockReason: String? = null
    )

    @Serializable
    private data class GeminiSafetyRating(
        val category: String,
        val probability: String
    )

    private fun LLMMessage.toGeminiContent(): GeminiContent? = when (this) {
        is LLMMessage.UserMessage -> GeminiContent(
            role = "user",
            parts = listOf(GeminiPart(text = content))
        )
        is LLMMessage.AssistantMessage -> GeminiContent(
            role = "model",
            parts = listOf(GeminiPart(text = content))
        )
        is LLMMessage.SystemMessage -> GeminiContent(
            role = "user",
            parts = listOf(GeminiPart(text = content))
        )
        is LLMMessage.ToolCallMessage -> null // Tool calls not supported in basic implementation
        is LLMMessage.ToolResponseMessage -> null // Tool responses not supported in basic implementation
    }

    override fun generate(
        messages: List<LLMMessage>,
        options: LLMOptions
    ): Flow<LLMMessage> = flow {
        // Handle system message by prepending it as a user message
        val systemMessage = options.systemPrompt ?: 
            messages.filterIsInstance<LLMMessage.SystemMessage>().lastOrNull()?.content
        
        // Prepare all messages for the conversation
        val allMessages = mutableListOf<GeminiContent>()
        
        // Add system message as a special user message if available
        if (systemMessage != null) {
            allMessages.add(GeminiContent(
                role = "user",
                parts = listOf(GeminiPart(text = "System: $systemMessage"))
            ))
        }
        
        // Add all non-system messages
        messages.filterNot { it is LLMMessage.SystemMessage }
            .mapNotNull { it.toGeminiContent() }
            .forEach { allMessages.add(it) }

        // Ensure there's at least one message
        if (allMessages.isEmpty()) {
            emit(LLMMessage.SystemMessage("Error: No valid messages found to send to Gemini API after filtering."))
            return@flow
        }

        // Create generation config
        val generationConfig = GeminiGenerationConfig(
            temperature = options.temperature,
            maxOutputTokens = options.maxTokens ?: 8192,
            stopSequences = options.stopSequences.takeIf { it.isNotEmpty() }
        )

        // Create request
        val request = GeminiRequest(
            contents = allMessages,
            generationConfig = generationConfig
        )

        val response: GeminiResponse = try {
            httpClient.post("$baseUrl/v1beta/models/$model:generateContent?key=$apiKey") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(request))
            }.body()
        } catch (e: Exception) {
            // Handle API errors gracefully
            emit(LLMMessage.SystemMessage("Error calling Gemini API: ${e.message}"))
            return@flow
        }

        // Check for API error
        if (response.error != null) {
            emit(LLMMessage.SystemMessage("Gemini API Error: ${response.error.message ?: "Unknown error"}"))
            return@flow
        }

        // Extract content from response
        val candidate = response.candidates?.firstOrNull()
        if (candidate == null) {
            emit(LLMMessage.SystemMessage("Error: No response candidates returned from Gemini API."))
            return@flow
        }

        val content = candidate.content?.parts?.firstOrNull()?.text ?: ""
        val rawResponse = Json.encodeToJsonElement(response)

        // Emit the assistant message generated by the API
        emit(LLMMessage.AssistantMessage(content, rawResponse))
    }
}
