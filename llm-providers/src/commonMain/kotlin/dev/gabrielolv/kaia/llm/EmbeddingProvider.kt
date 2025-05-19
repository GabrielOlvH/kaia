package dev.gabrielolv.kaia.llm

import dev.gabrielolv.kaia.llm.providers.embedding.GeminiEmbeddingProvider
import dev.gabrielolv.kaia.llm.providers.embedding.OpenAIEmbeddingProvider

// Potentially add a data class for a richer EmbeddingResult if needed later
// e.g., data class EmbeddingResult(val vector: List<Float>, val modelUsed: String, val promptTokens: Int)

interface EmbeddingProvider {
    /**
     * Generates embeddings for a list of texts.
     *
     * @param texts The list of strings to embed. Each string is a separate document or piece of text.
     * @param model The specific embedding model to use (optional). If null, the provider's default model is used.
     * @return A list of embedding vectors (List<Float>), corresponding to the input texts.
     *         The order of embeddings in the output list MUST match the order of texts in the input list.
     * @throws Exception if the embedding generation fails for any reason (e.g., API error, network issue).
     */
    suspend fun embed(texts: List<String>, model: String? = null): List<List<Float>>

    companion object {
        /**
         * Creates an OpenAI embedding provider.
         *
         * @param apiKey Your OpenAI API key.
         * @param baseUrl The base URL for the OpenAI API. Defaults to OpenAI's standard API endpoint.
         * @param defaultModel The default OpenAI embedding model to use if not specified in the `embed` call.
         *                     Example: "text-embedding-3-small".
         * @return An instance of [EmbeddingProvider] configured for OpenAI.
         */
        fun openAI(
            apiKey: String,
            baseUrl: String = "https://api.openai.com/v1",
            defaultModel: String = "text-embedding-3-small" // Verify latest recommended model via OpenAI docs
        ): EmbeddingProvider {
            return OpenAIEmbeddingProvider(apiKey, baseUrl, defaultModel)
        }

        /**
         * Creates an instance of a Gemini (Vertex AI) embedding provider.
         *
         * @param accessToken The access token for Google Cloud, typically obtained via gcloud auth.
         * @param projectId The Google Cloud Project ID.
         * @param region The Google Cloud region for the Vertex AI API. Defaults to "us-central1".
         * @param defaultModel The default model to use. Defaults to "text-embedding-005".
         * @return An [EmbeddingProvider] instance configured for Gemini (Vertex AI).
         */
        fun gemini(
            accessToken: String,
            projectId: String,
            region: String = "us-central1",
            defaultModel: String = "text-embedding-005"
        ): EmbeddingProvider {
            return GeminiEmbeddingProvider(
                accessToken = accessToken,
                projectId = projectId,
                region = region,
                defaultModel = defaultModel
            )
        }

        // Future providers (e.g., Anthropic, Cohere, local models) can be added here following the same pattern.
    }
}

/**
 * Convenience extension function to generate an embedding for a single text.
 *
 * @param text The string to embed.
 * @param model The specific embedding model to use (optional).
 * @return An embedding vector (List<Float>).
 */
suspend fun EmbeddingProvider.embed(text: String, model: String? = null): List<Float> {
    if (text.isEmpty()) {
        // Or throw an IllegalArgumentException, depending on desired behavior for empty strings
        // OpenAI API, for example, errors on empty strings for some models.
        // Returning an empty list might be unexpected for a single embedding request.
        // Consider what behavior makes most sense for your use cases.
        throw IllegalArgumentException("Input text for embedding cannot be empty.")
    }
    return this.embed(listOf(text), model).first()
}
