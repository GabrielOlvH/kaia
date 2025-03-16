package dev.gabrielolv.kaia.llm

import dev.gabrielolv.kaia.core.tools.ToolManager
import dev.gabrielolv.kaia.llm.providers.CustomProvider
import dev.gabrielolv.kaia.llm.providers.ToolCallingProvider

interface LLMProvider {
    /**
     * Generate a response from the LLM
     */
    suspend fun generate(prompt: String, options: LLMOptions = LLMOptions()): LLMResponse

    companion object {
        /**
         * Create an OpenAI-compatible LLM provider
         */
        fun openAI(
            apiKey: String,
            baseUrl: String = "https://api.openai.com/v1",
            model: String = "gpt-4-turbo",
            toolManager: ToolManager? = null
        ): ToolCallingProvider = ToolCallingProvider(apiKey, baseUrl, model, toolManager)

        /**
         * Create a custom LLM provider
         */
        fun custom(
            url: String,
            headers: Map<String, String>,
            requestTransformer: (String, LLMOptions) -> Any,
            responseTransformer: (Any) -> LLMResponse
        ): LLMProvider = CustomProvider(url, headers, requestTransformer, responseTransformer)
    }
}


