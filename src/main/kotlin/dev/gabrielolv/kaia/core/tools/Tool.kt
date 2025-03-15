package dev.gabrielolv.kaia.core.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Represents a tool that can be called by agents
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
 * Result of a tool execution
 */
@Serializable
data class ToolResult(
    val success: Boolean,
    val result: String,
    val metadata: JsonObject = JsonObject(emptyMap())
)

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