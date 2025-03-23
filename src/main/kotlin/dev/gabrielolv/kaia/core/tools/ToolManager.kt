package dev.gabrielolv.kaia.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Manages tool registration and execution
 */
class ToolManager(private val json: Json = Json) {
    private val tools = mutableMapOf<String, Tool>()

    var errorHandler: suspend (Tool, ToolResult) -> Unit = { tool, result -> }

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

        return try {
            val result = tool.execute(parameters)
            if (!result.success) {
                errorHandler(tool, result)
            }
            result
        } catch (e: Exception) {
            ToolResult(
                success = false,
                result = "Error executing tool: ${e.message}"
            )
        }
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