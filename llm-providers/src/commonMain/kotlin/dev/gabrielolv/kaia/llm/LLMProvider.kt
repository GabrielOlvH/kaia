package dev.gabrielolv.kaia.llm

import dev.gabrielolv.kaia.core.tools.ToolManager
import dev.gabrielolv.kaia.llm.providers.*
import kotlinx.coroutines.flow.Flow

interface LLMProvider {
    /**
     * Generate response based on a list of messages (history).
     *
     * @param messages The history of messages in the conversation.
     * @param options Configuration options for the generation.
     * @return A Flow emitting LLM messages generated during the process (e.g., Assistant response, Tool calls/responses).
     */
    fun generate(
        messages: List<LLMMessage>, // Changed from prompt: String
        options: LLMOptions = LLMOptions()
    ): Flow<LLMMessage>

    companion object {
        // --- Factory functions ---

        fun openAI(
            apiKey: String,
            baseUrl: String = "https://api.openai.com/v1",
            model: String = "gpt-4-turbo",
            toolManager: ToolManager? = null
        ): LLMProvider = if (toolManager != null) OpenAIToolsProvider(
            apiKey,
            baseUrl,
            model,
            toolManager
        ) else OpenAIProvider(apiKey, baseUrl, model)

        /**
         * Create a Gemini provider.
         *
         * @param apiKey The API key for Google Gemini API.
         * @param baseUrl The base URL for the API, defaults to Google's Gemini API endpoint.
         * @param model The model name to use, defaults to "gemini-1.5-flash".
         * @param toolManager Optional tool manager for function calling support.
         * @return A configured Gemini LLM provider.
         */
        fun gemini(
            apiKey: String,
            baseUrl: String = "https://generativelanguage.googleapis.com",
            model: String = "gemini-1.5-flash",
            toolManager: ToolManager? = null
        ): LLMProvider = if (toolManager != null) GeminiToolsProvider(
            apiKey,
            baseUrl,
            model,
            toolManager
        ) else GeminiProvider(apiKey, baseUrl, model)

        /**
         * Create an Anthropic provider.
         *
         * @param apiKey The API key for Anthropic API.
         * @param baseUrl The base URL for the API, defaults to Anthropic's API endpoint.
         * @param model The model name to use, defaults to "claude-3-7-sonnet-20250219".
         * @param toolManager Optional tool manager for tool use support.
         * @return A configured Anthropic LLM provider.
         */
        fun anthropic(
            apiKey: String,
            baseUrl: String = "https://api.anthropic.com/v1",
            model: String = "claude-3-7-sonnet-20250219",
            toolManager: ToolManager? = null
        ): LLMProvider = if (toolManager != null) AnthropicToolsProvider(
            apiKey,
            baseUrl,
            model,
            toolManager
        ) else AnthropicProvider(apiKey, baseUrl, model)

        /**
         * Create a custom LLM provider.
         * The requestTransformer now receives the message list.
         */
        fun custom(
            url: String,
            headers: Map<String, String>,
            // Updated requestTransformer signature
            requestTransformer: (List<LLMMessage>, LLMOptions) -> Any,
            responseTransformer: (Any) -> LLMResponse
        ): LLMProvider = CustomProvider(url, headers, requestTransformer, responseTransformer)
    }
}