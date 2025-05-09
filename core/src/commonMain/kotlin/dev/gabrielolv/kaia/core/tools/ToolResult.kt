package dev.gabrielolv.kaia.core.tools

// Removed: import dev.gabrielolv.kaia.core.tools.typed.validation.ValidationError - This seems to be an implementation detail, let's remove it from core
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolResult(
    val toolCallId: String,
    val result: String,
    val metadata: JsonObject = JsonObject(emptyMap()),
    // Removed: val validationErrors: List<ValidationError> = emptyList()
)
