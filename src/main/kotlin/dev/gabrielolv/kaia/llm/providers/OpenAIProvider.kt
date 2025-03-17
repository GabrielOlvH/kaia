package dev.gabrielolv.kaia.llm.providers

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
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                namingStrategy = JsonNamingStrategy.SnakeCase
            })
        }
    }

    @Serializable
    private data class OpenAIMessage(
        val role: String,
        val content: String
    )

    @Serializable
    private data class OpenAIRequest(
        val model: String,
        val messages: List<OpenAIMessage>,
        val temperature: Double = 0.7,
        val maxTokens: Int? = null,
        val stop: List<String>? = null
    )

    @Serializable
    private data class OpenAIChoice(
        val message: OpenAIMessage
    )

    @Serializable
    private data class OpenAIResponse(
        val choices: List<OpenAIChoice>
    )

    override suspend fun generate(prompt: String, options: LLMOptions): LLMResponse {
        val messages = mutableListOf<OpenAIMessage>()

        options.systemPrompt?.let {
            messages.add(OpenAIMessage("system", it))
        }

        messages.add(OpenAIMessage("user", prompt))

        val request = OpenAIRequest(
            model = model,
            messages = messages,
            temperature = options.temperature,
            maxTokens = options.maxTokens,
            stop = options.stopSequences.takeIf { it.isNotEmpty() }
        )

        val response: OpenAIResponse = client.post("$baseUrl/chat/completions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(request)
        }.body()

        val content = response.choices.firstOrNull()?.message?.content ?: ""
        return LLMResponse(content, Json.encodeToJsonElement(response))
    }
}