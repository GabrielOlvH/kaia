package dev.gabrielolv.kaia.core.tools.typed

import dev.gabrielolv.kaia.core.tools.Tool
import dev.gabrielolv.kaia.core.tools.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.reflect.KClass

/**
 * Enhanced implementation of Tool with typed parameters and auto schema generation
 */
abstract class TypedTool<T : ParamsClass>(
    override val name: String,
    override val description: String,
    private val paramsClass: KClass<T>,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : Tool {

    /**
     * Execute with typed parameters
     */
    abstract suspend fun executeTyped(parameters: ParamsInstance): ToolResult

    /**
     * Auto-generated parameter schema based on the parameter class
     */
    override val parameterSchema: JsonObject by lazy {
        ParamsClass.getInstance(paramsClass).getSchema()
    }

    /**
     * Execute implementation that handles deserialization
     */
    override suspend fun execute(parameters: JsonObject): ToolResult {
        return try {
            val instance = ParamsInstance(paramsClass).parseFromJson(parameters)
            val validationRes = instance.validate()
            if (!validationRes.isValid) {
                return ToolResult(false, "Validation failed", validationErrors = validationRes.errors)
            }
            executeTyped(instance)
        } catch (e: Exception) {
            ToolResult(
                success = false,
                result = "Parameter deserialization failed: ${e.message}"
            )
        }
    }
}
