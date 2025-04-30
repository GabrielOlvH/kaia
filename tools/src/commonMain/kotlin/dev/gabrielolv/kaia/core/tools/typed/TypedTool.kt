package dev.gabrielolv.kaia.core.tools.typed

import dev.gabrielolv.kaia.core.tools.Tool
import dev.gabrielolv.kaia.core.tools.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.reflect.KClass

/**
 * Enhanced implementation of Tool with typed parameters and auto schema generation
 */
abstract class TypedTool<T : ToolParameters>(
    override val name: String,
    override val description: String,
    private val paramsClass: KClass<T>,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : Tool {

    /**
     * Execute with typed parameters
     */
    abstract suspend fun executeTyped(parameters: ToolParametersInstance): ToolResult

    /**
     * Auto-generated parameter schema based on the parameter class
     */
    override val parameterSchema: JsonObject by lazy {
        ToolParameters.getInstance(paramsClass).getSchema()
    }

    /**
     * Execute implementation that handles deserialization
     */
    override suspend fun execute(toolCallId: String, parameters: JsonObject): ToolResult {
        return try {
            val instance = ToolParametersInstance(paramsClass).parseFromJson(parameters)
            val validationRes = instance.validate()
            if (!validationRes.isValid) {
                return ToolResult(toolCallId, false, "Validation failed: " + validationRes.errors.joinToString(", ") { "${it.property}: ${it.message}" })
            }
            executeTyped(instance)
        } catch (e: Exception) {
            ToolResult(
                toolCallId = toolCallId,
                success = false,
                result = "Parameter deserialization failed: ${e.message}"
            )
        }
    }
}
