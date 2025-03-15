package dev.gabrielolv.kaia.core.tools


import kotlinx.serialization.json.JsonObject

/**
 * Manages tool registration and execution
 */
class ToolManager {
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

        return try {
            tool.execute(parameters)
        } catch (e: Exception) {
            ToolResult(
                success = false,
                result = "Error executing tool: ${e.message}"
            )
        }
    }
}
