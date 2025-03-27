package dev.gabrielolv.kaia.core.tools

import dev.gabrielolv.kaia.core.ToolExecutionFailedException
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
    suspend fun executeTool(name: String, parameters: JsonObject): ToolResult {
        val tool = tools[name] ?: return ToolResult(
            success = false,
            result = "Tool '$name' not found"
        )

        val result = try {
            tool.execute(parameters)
        } catch (e: Exception) {
            throw ToolExecutionFailedException(tool, parameters, null, e)
        }

        if (!result.success) {
            throw ToolExecutionFailedException(tool, parameters, result, null)
        }
        return result
    }

    /**
     * Execute a tool from a JSON string of parameters
     */
    suspend fun executeToolFromJson(name: String, parametersJson: String): ToolResult {
        return try {
            val parameters = json.parseToJsonElement(parametersJson).jsonObject
            executeTool(name, parameters)
        } catch (e: Exception) {
            ToolResult(
                success = false,
                result = "Invalid JSON parameters: ${e.message}"
            )
        }
    }
}