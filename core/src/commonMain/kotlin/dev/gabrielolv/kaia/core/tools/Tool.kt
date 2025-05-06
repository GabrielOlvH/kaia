package dev.gabrielolv.kaia.core.tools

import io.ktor.utils.io.*
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
     */ @Throws(ToolExecutionFailedException::class, CancellationException::class)
    suspend fun execute(toolCallId: String, parameters: JsonObject): ToolResult
}

class BaseTool(
    override val name: String,
    override val description: String,
    override val parameterSchema: JsonObject,
    private val executor: suspend (String, JsonObject) -> ToolResult
) : Tool {
    override suspend fun execute(toolCallId: String, parameters: JsonObject): ToolResult {
        return executor(toolCallId, parameters)
    }
}
