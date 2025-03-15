package dev.gabrielolv.kaia.llm

import kotlinx.serialization.json.JsonElement

data class LLMResponse(
    val content: String,
    val rawResponse: JsonElement? = null
)