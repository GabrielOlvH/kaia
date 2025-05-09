package dev.gabrielolv.kaia.core.tools

import dev.gabrielolv.kaia.core.tenant.TenantPermission

sealed interface ToolError {
    data class ExecutionFailed(val reason: String, val cause: Throwable? = null) : ToolError
    object NoTenantContext : ToolError
    data class InsufficientPermissions(val missingPermissions: Set<TenantPermission>) : ToolError
    // Add other specific tool errors as needed
}