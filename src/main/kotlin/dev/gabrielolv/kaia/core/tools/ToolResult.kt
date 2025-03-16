package dev.gabrielolv.kaia.core.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolResult(
    val success: Boolean,
    val result: String,
    val metadata: JsonObject = JsonObject(emptyMap())
)