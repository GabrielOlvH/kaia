package dev.gabrielolv.kaia.core.tenant

import io.ktor.util.date.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@Serializable
data class Tenant(
    val id: String,
    val name: String,
    val settings: TenantSettings,
    val permissions: Set<TenantPermission>,
    val metadata: Map<String, String> = emptyMap(),
    val status: TenantStatus = TenantStatus.ACTIVE
)

@Serializable
data class TenantSettings(
    val maxConcurrentConversations: Int = 100,
    val allowedTools: Set<String> = emptySet(),
    // val rateLimits: RateLimits = RateLimits(), // Assuming RateLimits is defined elsewhere or simplified
    val customConfig: Map<String, String> = emptyMap()
)

enum class TenantPermission {
    DEFAULT_ACCESS, // Example permission
    // Add other specific tenant permissions as needed
}

enum class TenantStatus {
    ACTIVE, SUSPENDED, PENDING, DELETED
}

/**
 * TenantContext holds all relevant information for the current tenant's request.
 * This is the object that will be stored in the coroutine context.
 */
@Serializable
data class TenantContext(
    val tenant: Tenant,
    val sessionId: String, // Unique ID for the user's session
    val requestId: String, // Unique ID for the current request
    val timestamp: Long = getTimeMillis()
)

/**
 * CoroutineContext Element to hold TenantContext.
 */
data class TenantContextElement(
    val context: TenantContext
) : AbstractCoroutineContextElement(TenantContextElement) {
    companion object Key : CoroutineContext.Key<TenantContextElement>
}

/**
 * Extension function to easily access TenantContext from any CoroutineContext.
 */
fun CoroutineContext.tenantContext(): TenantContext? = this[TenantContextElement.Key]?.context

/**
 * Scope function to execute a block of code within a specific TenantContext.
 * The TenantContext will be available to all coroutines launched within this block.
 */
suspend fun <T> withTenantContext(
    tenantContext: TenantContext,
    block: suspend CoroutineScope.() -> T
): T = withContext(TenantContextElement(tenantContext)) {
    block()
}

