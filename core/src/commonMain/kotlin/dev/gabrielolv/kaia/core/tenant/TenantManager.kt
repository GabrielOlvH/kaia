package dev.gabrielolv.kaia.core.tenant

/**
 * Manages tenant lifecycle and retrieval.
 * Implementations would typically fetch tenant data from a persistent store or configuration.
 */
interface TenantManager {
    /**
     * Retrieves a tenant by its ID.
     * Returns the Tenant object if found, null otherwise.
     * In a more robust system, this might return an Either<Error, Tenant>.
     */
    suspend fun getTenant(tenantId: String): Tenant?

    // Add other tenant management functions as needed, e.g.:
    // suspend fun createTenant(tenantDetails: NewTenantRequest): Either<Error, Tenant>
    // suspend fun updateTenant(tenantId: String, updates: TenantUpdateRequest): Either<Error, Tenant>
    // suspend fun listTenants(page: Int, size: Int): List<Tenant>
}

/**
 * A simple TenantManager implementation that serves a single, predefined tenant.
 * Useful for single-tenant deployments or as a default/test implementation.
 */
class SingleTenantManager(
    private val tenant: Tenant = Tenant(
        id = "default-tenant",
        name = "Default Tenant",
        settings = TenantSettings(
            allowedTools = setOf("*") // Allows all tools by default
        ),
        permissions = setOf(TenantPermission.DEFAULT_ACCESS),
        metadata = mapOf("managerType" to "SingleTenantManager")
    )
) : TenantManager {
    override suspend fun getTenant(tenantId: String): Tenant? {
        // Only return the tenant if the provided ID matches the single tenant's ID
        return if (tenantId == tenant.id) {
            tenant
        } else {
            null // Or throw an exception, or return an error type
        }
    }
}