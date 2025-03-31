package dev.gabrielolv.kaia.llm

import kotlinx.serialization.json.JsonElement

/**
 * Configuration options for LLM requests
 */
data class LLMOptions(
    val temperature: Double = 0.7,
    val maxTokens: Int? = null,
    val stopSequences: List<String> = emptyList(),
    val systemPrompt: String? = null,
    val additionalParameters: Map<String, JsonElement> = emptyMap(),
    val responseFormat: String = "text",
    val historySize: Int? = 10
)
