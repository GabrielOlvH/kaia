package dev.gabrielolv.kaia.core.tools.builders

import dev.gabrielolv.kaia.core.tools.Tool
import dev.gabrielolv.kaia.core.tools.ToolResult
import dev.gabrielolv.kaia.core.tools.typed.ToolParameters
import dev.gabrielolv.kaia.core.tools.typed.ToolParametersInstance
import dev.gabrielolv.kaia.core.tools.typed.TypedTool
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass

/**
 * Builder for creating typed tool instances
 */
class TypedToolBuilder<T : ToolParameters>(
    private val paramsClass: KClass<T>,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    var name: String = ""
    var description: String = ""
    private var executor: (suspend (ToolParametersInstance) -> ToolResult)? = null

    fun execute(block: suspend (ToolParametersInstance) -> ToolResult) {
        executor = block
    }

    fun build(): Tool {
        require(name.isNotEmpty()) { "Tool name cannot be empty" }
        require(description.isNotEmpty()) { "Tool description cannot be empty" }
        require(executor != null) { "Tool executor must be specified" }

        return object : TypedTool<T>(name, description, paramsClass, json) {
            override suspend fun executeTyped(parameters: ToolParametersInstance): ToolResult {
                return executor!!.invoke(parameters)
            }
        }
    }
}

/**
 * Create a typed tool using DSL
 */
inline fun <reified T : ToolParameters> createTool(
    json: Json = Json { ignoreUnknownKeys = true },
    block: TypedToolBuilder<T>.() -> Unit
): Tool {
    val builder = TypedToolBuilder(T::class, json)
    builder.block()
    return builder.build()
}
