package dev.gabrielolv.kaia.core.tools.typed

import dev.gabrielolv.kaia.core.SchemaGenerator
import dev.gabrielolv.kaia.core.tools.Tool
import dev.gabrielolv.kaia.core.tools.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/**
 * Enhanced implementation of Tool with typed parameters and auto schema generation
 */
abstract class TypedTool<T : Any>(
    override val name: String,
    override val description: String,
    private val paramClass: KClass<T>,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : Tool {

    /**
     * Execute with typed parameters
     */
    abstract suspend fun executeTyped(parameters: T): ToolResult

    /**
     * Auto-generated parameter schema based on the parameter class
     */
    override val parameterSchema: JsonObject by lazy {
        val serializer = json.serializersModule.serializer(paramClass.java)
        SchemaGenerator.generateSchemaFromDescriptor(serializer.descriptor)
    }

    /**
     * Execute implementation that handles deserialization
     */
    override suspend fun execute(parameters: JsonObject): ToolResult {
        return try {
            val serializer = json.serializersModule.serializer(paramClass.java)
            val typedParams = json.decodeFromJsonElement(serializer, parameters) as T
            executeTyped(typedParams)
        } catch (e: Exception) {
            ToolResult(
                success = false,
                result = "Parameter deserialization failed: ${e.message}"
            )
        }
    }
}