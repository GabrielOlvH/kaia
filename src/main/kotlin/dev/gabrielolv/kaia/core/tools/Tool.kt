package dev.gabrielolv.kaia.core.tools

import dev.gabrielolv.kaia.core.SchemaGenerator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import kotlin.reflect.KClass


/**
 * Result of a tool execution
 */
@Serializable
data class ToolResult(
    val success: Boolean,
    val result: String,
    val metadata: JsonObject = JsonObject(emptyMap())
)

/**
 * Tool interface with typed parameter execution support
 */
interface Tool {
    val name: String
    val description: String
    val parameterSchema: JsonObject

    /**
     * Execute the tool with the given parameters
     */
    suspend fun execute(parameters: JsonObject): ToolResult
}

/**
 * Tool implementation supporting strongly typed parameters
 */


/**
 * Enhanced implementation of TypedTool with auto schema generation
 */
abstract class EnhancedTypedTool<T : Any>(
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
        try {
            val serializer = json.serializersModule.serializer(paramClass.java)
            SchemaGenerator.generateSchemaFromDescriptor(serializer.descriptor)
        } catch (e: Exception) {
            // Fallback to empty schema if generation fails
            JsonObject(emptyMap())
        }
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

class TypedToolBuilder<T : Any>(
    private val paramClass: KClass<T>,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    var name: String = ""
    var description: String = ""
    private var executor: (suspend (T) -> ToolResult)? = null

    fun execute(block: suspend (T) -> ToolResult) {
        executor = block
    }

    fun build(): Tool {
        require(name.isNotEmpty()) { "Tool name cannot be empty" }
        require(description.isNotEmpty()) { "Tool description cannot be empty" }
        require(executor != null) { "Tool executor must be specified" }

        return object : EnhancedTypedTool<T>(name, description, paramClass, json) {
            override suspend fun executeTyped(parameters: T): ToolResult {
                return executor!!.invoke(parameters)
            }
        }
    }
}

/**
 * Create a typed tool using DSL
 */
inline fun <reified T : Any> typedTool(
    json: Json = Json { ignoreUnknownKeys = true },
    block: TypedToolBuilder<T>.() -> Unit
): Tool {
    val builder = TypedToolBuilder<T>(T::class, json)
    builder.block()
    return builder.build()
}


/**
 * Builder for creating Tool instances
 */
class ToolBuilder {
    var name: String = ""
    var description: String = ""
    var parameterSchema: JsonObject = JsonObject(emptyMap())
    var executor: suspend (JsonObject) -> ToolResult = {
        ToolResult(false, "Tool execution not implemented")
    }

    fun parameters(block: ParameterSchemaBuilder.() -> Unit) {
        val builder = ParameterSchemaBuilder()
        builder.block()
        parameterSchema = builder.build()
    }

    fun build(): Tool = BaseTool(
        name = name,
        description = description,
        parameterSchema = parameterSchema,
        executor = executor
    )
}

/**
 * Builder for creating JSON Schema for tool parameters
 */
class ParameterSchemaBuilder {
    private val properties = mutableMapOf<String, JsonObject>()
    private val required = mutableListOf<String>()

    fun property(
        name: String,
        type: String,
        description: String,
        isRequired: Boolean = false,
        additionalProperties: Map<String, JsonElement> = emptyMap()
    ) {
        val property = buildJsonObject {
            put("type", type)
            put("description", description)
            for ((key, value) in additionalProperties) {
                put(key, value)
            }
        }

        properties[name] = property
        if (isRequired) {
            required.add(name)
        }
    }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for ((name, schema) in properties) {
                put(name, schema)
            }
        }
        putJsonArray("required") {
            required.forEach { add(it) }
        }
    }
}

/**
 * Default implementation of the Tool interface
 */
private class BaseTool(
    override val name: String,
    override val description: String,
    override val parameterSchema: JsonObject,
    private val executor: suspend (JsonObject) -> ToolResult
) : Tool {
    override suspend fun execute(parameters: JsonObject): ToolResult {
        return executor(parameters)
    }
}

// Create a standard tool
fun createTool(block: ToolBuilder.() -> Unit): Tool {
    val builder = ToolBuilder()
    builder.block()
    return builder.build()
}