package dev.gabrielolv.kaia.core.tools

import arrow.core.Either
import arrow.core.left
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Manages tool registration and execution
 */
class ToolManager(private val json: Json = Json) {
    private val tools = mutableMapOf<String, Tool>()

    /**
     * Register a tool
     */
    fun registerTool(tool: Tool) {
        tools[tool.name] = tool
    }

    /**
     * Get a registered tool by name
     */
    fun getTool(name: String): Tool? = tools[name]

    /**
     * Get all registered tools
     */
    fun getAllTools(): List<Tool> = tools.values.toList()

    /**
     * Execute a tool by name with the given parameters
     */
    suspend fun executeTool(toolCallId: String, name: String, parameters: JsonObject): Either<ToolError, ToolResult> {
        val tool = tools[name] ?: return Either.Left(ToolError.ExecutionFailed(
            reason = "Tool not found: $name"
        ))

        return try {
            tool.execute(toolCallId, parameters)
        } catch (e: Exception) {
            Either.Left(ToolError.ExecutionFailed(
                reason = "Tool execution failed: ${e.message}",
                cause = e
            ))
        }

    }

    /**
     * Execute a tool from a JSON string of parameters
     */
    suspend fun executeToolFromJson(toolCallId: String, name: String, parametersJson: String): Either<ToolError, ToolResult> {
        return try {
            val parameters = json.parseToJsonElement(parametersJson).jsonObject
            executeTool(toolCallId, name, parameters)
        } catch (e: Exception) {
            Either.Left(ToolError.ExecutionFailed(
                reason = "Failed to parse parameters JSON: ${e.message}",
                cause = e
            ))
        }
    }
}
