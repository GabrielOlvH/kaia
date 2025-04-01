package dev.gabrielolv.kaia.llm

import dev.gabrielolv.kaia.core.tools.ToolManager
import dev.gabrielolv.kaia.llm.providers.CustomProvider
import dev.gabrielolv.kaia.llm.providers.OpenAIProvider
import dev.gabrielolv.kaia.llm.providers.OpenAIToolsProvider
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