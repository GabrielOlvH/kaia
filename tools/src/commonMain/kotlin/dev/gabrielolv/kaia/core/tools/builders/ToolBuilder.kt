package dev.gabrielolv.kaia.core.tools.builders

import dev.gabrielolv.kaia.core.tools.BaseTool
import dev.gabrielolv.kaia.core.tools.Tool
import dev.gabrielolv.kaia.core.tools.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * Builder for creating Tool instances
 */
class ToolBuilder {
    var name: String = ""
    var description: String = ""
    var parameterSchema: JsonObject = JsonObject(emptyMap())
    var executor: suspend (String, JsonObject) -> ToolResult = { _, _ ->
        ToolResult("", false, "Tool execution not implemented")
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
 * Create a standard tool
 */
fun createTool(block: ToolBuilder.() -> Unit): Tool {
    val builder = ToolBuilder()
    builder.block()
    return builder.build()
}