package dev.gabrielolv.kaia.core.tools

import kotlinx.serialization.json.JsonObject

class ToolExecutionFailedException(val tool: Tool, val parameters: JsonObject, val result: ToolResult?, cause: Exception?) : Exception("Tool execution failed!\n${tool.name}: ${tool.description}\n${tool.parameterSchema}\n\nInvocation parameters: ${parameters}\nResult: $result", cause)
