package dev.gabrielolv.kaia.core.tools

import arrow.core.Either
import dev.gabrielolv.kaia.core.tenant.TenantContext
import dev.gabrielolv.kaia.core.tenant.tenantContext
import io.ktor.utils.io.*
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.coroutineContext


/**
 * Tool interface with typed parameter execution support and tenant context awareness.
 */
interface Tool {
    val name: String
    val description: String
    val parameterSchema: JsonObject
    // val requiredTenantPermissions: Set<TenantPermission> // Add if tools declare permissions upfront

    /**
     * Execute the tool with the given parameters, leveraging tenant context from coroutines.
     * @return Either a ToolError or a ToolResult.
     */
    suspend fun execute(toolCallId: String, parameters: JsonObject): Either<ToolError, ToolResult>

    /**
     * Internal execution logic that requires TenantContext. Tools should implement this.
     * This allows the public execute to handle context fetching.
     */
    suspend fun executeWithContext(toolCallId: String, parameters: JsonObject, tenantContext: TenantContext): Either<ToolError, ToolResult>
}

class BaseTool(
    override val name: String,
    override val description: String,
    override val parameterSchema: JsonObject,
    // override val requiredTenantPermissions: Set<TenantPermission> = emptySet(), // Add if needed
    private val executor: suspend (toolCallId: String, parameters: JsonObject, tenantContext: TenantContext) -> Either<ToolError, ToolResult>
) : Tool {

    override suspend fun execute(toolCallId: String, parameters: JsonObject): Either<ToolError, ToolResult> {
        val tenantContext = coroutineContext.tenantContext()
            ?: return Either.Left(ToolError.NoTenantContext)

        // Permission check: Verify if the tool is allowed for the current tenant
        if (!tenantContext.tenant.settings.canUseTool(this.name)) {
            // Assuming ToolError.ToolNotAllowedError is defined as suggested.
            // If not, replace with a more generic error or a specific string in ExecutionFailed.
            return Either.Left(ToolError.ExecutionFailed("Tool '${this.name}' is not allowed for tenant '${tenantContext.tenant.id}'."))
        }
        

//         if (requiredTenantPermissions.any { it !in tenantContext.tenant.permissions }) {
//             return Either.Left(ToolError.InsufficientPermissions(requiredTenantPermissions - tenantContext.tenant.permissions))
//         }

        return executeWithContext(toolCallId, parameters, tenantContext)
    }

    override suspend fun executeWithContext(toolCallId: String, parameters: JsonObject, tenantContext: TenantContext): Either<ToolError, ToolResult> {
        return try {
            executor(toolCallId, parameters, tenantContext)
        } catch (e: CancellationException) {
            throw e // Re-throw cancellation exceptions
        } catch (e: Exception) {
            Either.Left(ToolError.ExecutionFailed("BaseTool execution failed: ${e.message}", e))
        }
    }
}