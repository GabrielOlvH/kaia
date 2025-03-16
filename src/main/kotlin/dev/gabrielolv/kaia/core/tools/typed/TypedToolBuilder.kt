package dev.gabrielolv.kaia.core.tools.typed

import dev.gabrielolv.kaia.core.tools.Tool
import dev.gabrielolv.kaia.core.tools.ToolResult
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass

/**
 * Builder for creating typed tool instances
 */
class TypedToolBuilder<T : Any>(
    private val paramClass: KClass<T>,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    var name: String = ""
    var description: String = ""
    private var executor: (suspend (T) -> ToolResult)? = null

    fun execute(block: suspend (T) -> ToolResult) {
        executor = block
    }

    fun build(): Tool {
        require(name.isNotEmpty()) { "Tool name cannot be empty" }
        require(description.isNotEmpty()) { "Tool description cannot be empty" }
        require(executor != null) { "Tool executor must be specified" }

        return object : TypedTool<T>(name, description, paramClass, json) {
            override suspend fun executeTyped(parameters: T): ToolResult {
                return executor!!.invoke(parameters)
            }
        }
    }
}

/**
 * Create a typed tool using DSL
 */
inline fun <reified T : Any> createTool(
    json: Json = Json { ignoreUnknownKeys = true },
    block: TypedToolBuilder<T>.() -> Unit
): Tool {
    val builder = TypedToolBuilder<T>(T::class, json)
    builder.block()
    return builder.build()
}