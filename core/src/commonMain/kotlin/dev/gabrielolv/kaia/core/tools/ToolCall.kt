package dev.gabrielolv.kaia.core.tools

import kotlinx.serialization.Serializable

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String // Keep as String (JSON) for now, consistent with current LLM messages
)