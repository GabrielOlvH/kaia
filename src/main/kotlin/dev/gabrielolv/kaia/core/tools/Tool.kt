package dev.gabrielolv.kaia.core.tools

import dev.gabrielolv.kaia.core.ToolExecutionFailedException
import kotlinx.serialization.json.JsonObject


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
    @Throws(ToolExecutionFailedException::class)
    suspend fun execute(parameters: JsonObject): ToolResult
}

internal class BaseTool(
    override val name: String,
    override val description: String,
    override val parameterSchema: JsonObject,
    private val executor: suspend (JsonObject) -> ToolResult
) : Tool {
    override suspend fun execute(parameters: JsonObject): ToolResult {
        return executor(parameters)
    }
}
