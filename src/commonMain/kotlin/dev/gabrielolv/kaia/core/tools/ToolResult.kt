package dev.gabrielolv.kaia.core.tools

import dev.gabrielolv.kaia.core.tools.typed.validation.ValidationError
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolResult(
    val success: Boolean,
    val result: String,
    val metadata: JsonObject = JsonObject(emptyMap()),
    val validationErrors: List<ValidationError> = emptyList(),
)