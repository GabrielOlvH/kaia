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

// --- Data classes for Vertex AI (Gemini) Embedding API Request and Response ---

@Serializable
private data class VertexAIEmbeddingInstance(
    val content: String,
    // task_type defaults to "RETRIEVAL_QUERY" if not specified, which is fine for general embeddings.
    // We could add task_type and title as optional parameters if more specific control is needed later.
    // val task_type: String? = null, 
    // val title: String? = null
)

@Serializable
private data class VertexAIEmbeddingParameters(
    @SerialName("autoTruncate") val autoTruncate: Boolean? = true // Defaults to true in API if not sent
    // val outputDimensionality: Int? = null // Optional: to reduce embedding dimensions
)

@Serializable
private data class VertexAIEmbeddingRequest(
    val instances: List<VertexAIEmbeddingInstance>,
    val parameters: VertexAIEmbeddingParameters? = null
)

@Serializable
private data class VertexAIEmbeddingStatistics(
    val truncated: Boolean? = null, // API doc shows it as boolean, make nullable for robustness
    @SerialName("token_count") val tokenCount: Int? = null // API doc shows it as integer
)

@Serializable
private data class VertexAIEmbeddingValues(
    val statistics: VertexAIEmbeddingStatistics? = null,
    val values: List<Float>
)

@Serializable
private data class VertexAIEmbeddingPrediction(
    val embeddings: VertexAIEmbeddingValues
)

@Serializable
private data class VertexAIEmbeddingResponse(
    val predictions: List<VertexAIEmbeddingPrediction>
    // The response might also contain "deployedModelId" and other metadata, ignoring for now.
)


internal class GeminiEmbeddingProvider(
    private val accessToken: String, // User-provided access token (e.g., from gcloud auth print-access-token)
    private val projectId: String,   // Google Cloud Project ID
    private val region: String = "us-central1", // Common default, make configurable if needed
    private val defaultModel: String = "text-embedding-005" // Or text-multilingual-embedding-002 for broader language
) : EmbeddingProvider {

    private val client by lazy {
        HttpClient(CIO) { // Consider making Ktor engine configurable
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    coerceInputValues = true // Helpful if API returns fields that are sometimes null/missing
                })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 90000 // 90 seconds, embeddings can take time
            }
        }
    }

    companion object {
        private const val MAX_INSTANCES_PER_REQUEST = 250
    }

    override suspend fun embed(texts: List<String>, model: String?): List<List<Float>> {
        if (texts.isEmpty()) {
            return emptyList()
        }
        // Vertex AI API may have issues with completely empty strings for 'content'.
        if (texts.any { it.trim().isEmpty() }) {
            throw IllegalArgumentException("Input texts for embedding cannot contain empty or whitespace-only strings for Gemini/Vertex AI provider.")
        }

        val effectiveModel = model ?: defaultModel
        val allEmbeddings = mutableListOf<List<Float>>()

        texts.chunked(MAX_INSTANCES_PER_REQUEST).forEach { chunk ->
            val instances = chunk.map { VertexAIEmbeddingInstance(content = it) }
            val requestBody = VertexAIEmbeddingRequest(instances = instances)
            
            val endpoint = "https://${region}-aiplatform.googleapis.com/v1/projects/${projectId}/locations/${region}/publishers/google/models/${effectiveModel}:predict"

            try {
                val response: VertexAIEmbeddingResponse = client.post(endpoint) {
                    bearerAuth(accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }.body()

                // Assuming the order of predictions matches the order of instances sent.
                // The API documentation typically implies this, but it's a common assumption.
                response.predictions.forEach {
                    allEmbeddings.add(it.embeddings.values)
                }
            } catch (e: ClientRequestException) {
                val errorBody = try { e.response.bodyAsText() } catch (_: Exception) { "No additional error body." }
                throw RuntimeException("Vertex AI (Gemini) API request for embeddings failed with status ${e.response.status}. Model: $effectiveModel. Endpoint: $endpoint. Response: $errorBody", e)
            } catch (e: Exception) {
                throw RuntimeException("Vertex AI (Gemini) API request for embeddings failed. Model: $effectiveModel. Endpoint: $endpoint. Error: ${e.message}", e)
            }
        }
        return allEmbeddings
    }
}
