package dev.gabrielolv.kaia.core.tools.typed

import dev.gabrielolv.kaia.core.tools.Tool
import dev.gabrielolv.kaia.core.tools.ToolResult
import dev.gabrielolv.kaia.core.tools.ToolError
import dev.gabrielolv.kaia.core.tenant.TenantContext
import dev.gabrielolv.kaia.core.tenant.TenantPermission
import dev.gabrielolv.kaia.core.tenant.tenantContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.reflect.KClass
import kotlin.coroutines.coroutineContext
import arrow.core.Either
import arrow.core.left
import arrow.core.right

/**
 * Enhanced implementation of Tool with typed parameters, auto schema generation, and tenant context awareness.
 */
abstract class TypedTool<T : ToolParameters>(
    override val name: String,
    override val description: String,
    private val paramsClass: KClass<T>,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) : Tool {

    /**
     * Execute with typed parameters and tenant context.
     */
    abstract suspend fun executeTyped(toolCallId: String, parameters: ToolParametersInstance, tenantContext: TenantContext): Either<ToolError, ToolResult>

    /**
     * Auto-generated parameter schema based on the parameter class
     */
    override val parameterSchema: JsonObject by lazy {
        ToolParameters.getInstance(paramsClass).getSchema()
    }

    /**
     * Public execute method that fetches tenant context and delegates.
     */
    override suspend fun execute(toolCallId: String, parameters: JsonObject): Either<ToolError, ToolResult> {
        val tenantContext = coroutineContext.tenantContext()
            ?: return ToolError.NoTenantContext.left()

        // Permission check: Verify if the tool is allowed for the current tenant
        if (!tenantContext.tenant.settings.allowedTools.contains(this.name)) {
            // Assuming ToolError.ToolNotAllowedError is defined as suggested.
            // If not, replace with a more generic error or a specific string in ExecutionFailed.
            return ToolError.ExecutionFailed("Tool '${this.name}' is not allowed for tenant '${tenantContext.tenant.id}'.").left()
        }
        
        return executeWithContext(toolCallId, parameters, tenantContext)
    }

    /**
     * Execute implementation that handles deserialization and calls executeTyped.
     */
    override suspend fun executeWithContext(toolCallId: String, parameters: JsonObject, tenantContext: TenantContext): Either<ToolError, ToolResult> {
        return try {
            val instance = ToolParametersInstance(paramsClass).parseFromJson(parameters)
            val validationRes = instance.validate()
            if (!validationRes.isValid) {
                val errorMessages = validationRes.errors.joinToString(", ") { "${it.property}: ${it.message}" }
                return ToolError.ExecutionFailed("Validation failed: $errorMessages").left()
            }
            executeTyped(toolCallId, instance, tenantContext)
        } catch (e: Exception) {
            ToolError.ExecutionFailed("Parameter deserialization or typed execution failed: ${e.message}", e).left()
        }
    }
}
