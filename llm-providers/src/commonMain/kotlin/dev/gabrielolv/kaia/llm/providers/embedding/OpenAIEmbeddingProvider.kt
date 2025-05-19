package dev.gabrielolv.kaia.llm.providers.embedding

import dev.gabrielolv.kaia.llm.EmbeddingProvider
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// --- Data classes for OpenAI API Request and Response ---

@Serializable
private data class OpenAIEmbeddingRequest(
    val input: List<String>,
    val model: String,
    @SerialName("encoding_format") val encodingFormat: String = "float" // Default to float for List<Float>
)

@Serializable
private data class OpenAIEmbeddingData(
    val embedding: List<Float>,
    val index: Int,
    @SerialName("object") val objectType: String // "object" is a Kotlin keyword
)

@Serializable
private data class OpenAIUsage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int
)

@Serializable
private data class OpenAIEmbeddingResponse(
    val data: List<OpenAIEmbeddingData>,
    val model: String,
    @SerialName("object") val objectType: String, // e.g., "list"
    val usage: OpenAIUsage
)

// --- OpenAIEmbeddingProvider Implementation ---

internal class OpenAIEmbeddingProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val defaultModel: String = "text-embedding-3-small"
) : EmbeddingProvider {

    private val client by lazy {
        HttpClient(CIO) { // Consider making the Ktor engine configurable or using a common one from your project
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true 
                })
            }
            // Default request configuration, e.g., for timeouts. Can be shared across providers.
            install(HttpTimeout) {
                requestTimeoutMillis = 60000 // 60 seconds, adjust as needed
            }
            // Consider adding a defaultRequest block for common headers or settings if you have project-wide Ktor conventions
        }
    }

    override suspend fun embed(texts: List<String>, model: String?): List<List<Float>> {
        if (texts.isEmpty()) {
            return emptyList()
        }
        // Ensure no individual text string is empty, as OpenAI API might error.
        // This check is important for API compliance.
        if (texts.any { it.trim().isEmpty() }) { // Also checking for strings that are only whitespace
            throw IllegalArgumentException("Input texts for embedding cannot contain empty or whitespace-only strings for OpenAI provider.")
        }

        val effectiveModel = model ?: defaultModel
        val requestBody = OpenAIEmbeddingRequest(
            input = texts,
            model = effectiveModel
            // encodingFormat is defaulted in the data class to "float"
        )

        try {
            val response: OpenAIEmbeddingResponse = client.post("$baseUrl/embeddings") {
                bearerAuth(apiKey)
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.body()

            // OpenAI API documentation generally states embeddings are returned in order.
            // Sorting by 'index' is a robust way to guarantee it if there's any doubt or if the API behavior changes.
            return response.data.sortedBy { it.index }.map { it.embedding }
        } catch (e: ClientRequestException) {
            // More specific error for HTTP client errors (4xx, 5xx)
            val errorBody = try { e.response.bodyAsText() } catch (_: Exception) { "No additional error body could be retrieved." }
            throw RuntimeException("OpenAI API request for embeddings failed with status ${e.response.status}. Response: $errorBody", e)
        } catch (e: Exception) {
            // General catch-all for other issues (network, serialization, etc.)
            throw RuntimeException("OpenAI API request for embeddings failed: ${e.message}", e)
        }
    }
}
