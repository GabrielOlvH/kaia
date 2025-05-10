package dev.gabrielolv.kaia.core.tools.builders

import arrow.core.Either
import dev.gabrielolv.kaia.core.tenant.TenantContext
import dev.gabrielolv.kaia.core.tools.BaseTool
import dev.gabrielolv.kaia.core.tools.Tool
import dev.gabrielolv.kaia.core.tools.ToolError
import dev.gabrielolv.kaia.core.tools.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * Builder for creating Tool instances
 */
class ToolBuilder {
    var name: String = ""
    var description: String = ""
    var parameterSchema: JsonObject = JsonObject(emptyMap())
    var executor: suspend (String, JsonObject, TenantContext) -> Either<ToolError, ToolResult> = { _, _, _ ->
        Either.Left(ToolError.ExecutionFailed("Tool not implemented"))
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