package dev.gabrielolv.kaia.core.tools

import dev.gabrielolv.kaia.core.tools.typed.ToolParameters
import dev.gabrielolv.kaia.core.tools.typed.ToolParametersInstance
import dev.gabrielolv.kaia.core.tools.typed.TypedTool
import kotlin.reflect.KClass

/**
 * DSL marker annotation to control receiver scope.
 */
@DslMarker
annotation class ToolRegistrationDsl

/**
 * Provides the scope for the tool registration DSL.
 * Allows registering tools within the `registerTools { ... }` block.
 */
@ToolRegistrationDsl
class ToolRegistrationScope(private val toolManager: ToolManager) {

    /**
     * Registers a standard [Tool] implementation.
     *
     * @param tool The tool instance to register.
     */
    fun tool(tool: Tool) {
        toolManager.registerTool(tool)
    }

    /**
     * Registers a [TypedTool] by providing its core components.
     * This variant requires explicitly passing the [KClass] for the parameters.
     *
     * @param T The type of the [ToolParameters] subclass.
     * @param name The name of the tool.
     * @param description A description of what the tool does.
     * @param paramsClass The [KClass] of the parameter data class (must inherit from [ToolParameters]).
     * @param executor A suspend lambda function that takes the validated [ToolParametersInstance]
     *                 and performs the tool's action, returning a [ToolResult].
     */
    fun <T : ToolParameters> typedTool(
        name: String,
        description: String,
        paramsClass: KClass<T>,
        executor: suspend (parameters: ToolParametersInstance) -> ToolResult
    ) {
        val tool = object : TypedTool<T>(name, description, paramsClass) {
            override suspend fun executeTyped(parameters: ToolParametersInstance): ToolResult {
                // Input parameters are already parsed and validated by TypedTool base class
                return executor(parameters)
            }
        }
        toolManager.registerTool(tool)
    }

    /**
     * Registers a [TypedTool] using a reified type parameter for convenience.
     * This avoids the need to explicitly pass `T::class`.
     *
     * Example usage:
     * ```kotlin
     * typedTool<MySearchParams>(name = "search", description = "Searches stuff") { params ->
     *     // Tool logic here, params is ToolParametersInstance
     *     ToolResult(true, "Found stuff")
     * }
     * ```
     *
     * @param T The reified type of the [ToolParameters] subclass.
     * @param name The name of the tool.
     * @param description A description of what the tool does.
     * @param executor A suspend lambda function that takes the validated [ToolParametersInstance]
     *                 and performs the tool's action, returning a [ToolResult].
     */
    inline fun <reified T : ToolParameters> typedTool(
        name: String,
        description: String,
        noinline executor: suspend (parameters: ToolParametersInstance) -> ToolResult
    ) {
        // Call the other typedTool function, passing T::class automatically
        typedTool(name, description, T::class, executor)
    }
}

/**
 * Extension function on [ToolManager] to initiate the tool registration DSL.
 *
 * Example:
 * ```kotlin
 * val manager = ToolManager()
 * manager.registerTools {
 *     tool(MyLegacyTool())
 *     typedTool<MyParams>(...) { ... }
 * }
 * ```
 *
 * @param block A lambda with [ToolRegistrationScope] as its receiver, where tools can be registered.
 */
fun ToolManager.registerTools(block: ToolRegistrationScope.() -> Unit) {
    ToolRegistrationScope(this).apply(block)
}
