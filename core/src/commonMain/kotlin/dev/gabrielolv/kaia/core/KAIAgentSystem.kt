package dev.gabrielolv.kaia.core

import arrow.core.Either
import dev.gabrielolv.kaia.core.agents.Agent
import dev.gabrielolv.kaia.core.tenant.SingleTenantManager
import dev.gabrielolv.kaia.core.tenant.TenantContext
import dev.gabrielolv.kaia.core.tenant.TenantManager
import dev.gabrielolv.kaia.core.tenant.withTenantContext
import dev.gabrielolv.kaia.core.tools.Tool
import dev.gabrielolv.kaia.core.tools.ToolManager
import dev.gabrielolv.kaia.llm.LLMMessage
import kotlinx.coroutines.flow.Flow


sealed interface SystemError {
    data class TenantNotFound(val tenantId: String) : SystemError
    data class ConversationOperationFailed(val message: String) : SystemError
}

/**
 * Builder for configuring and creating a KAIAgentSystem.
 */
class KAIAgentSystemBuilder internal constructor() { // Make constructor internal
    internal val agents: MutableList<Agent> = mutableListOf() // Keep internal for helpers
    private var directorAgentId: String? = null
    private val toolManager = ToolManager()
    internal var tenantManager: TenantManager = SingleTenantManager()

    /**
     * Adds a specialized agent to the system.
     * Use helper extensions like `addLLMAgent` or `addDatabaseAgent` for common types.
     */
    fun addAgent(agent: Agent): Agent {
        this.agents.add(agent)
        return agent
    }

    /**
     * Registers a tool with the system's ToolManager.
     */
    fun registerTool(tool: Tool) {
        toolManager.registerTool(tool)
    }

    // Method to allow user to set a custom TenantManager
    fun withTenantManager(manager: TenantManager): KAIAgentSystemBuilder {
        this.tenantManager = manager
        return this
    }

    fun getAgentDatabase(): Map<String, String> {
        return agents.associate { agent -> agent.id to agent.description }
    }

    /**
     * Designates a previously added agent as the director agent for the system.
     * This agent will be responsible for routing tasks to other specialized agents.
     *
     * @param agentId The unique ID of the agent to designate as the director.
     *                This agent must have been added via `addAgent` or a helper extension.
     * @throws IllegalArgumentException if the provided agentId does not correspond to any added agent.
     */
    fun designateDirector(agentId: String) {
        require(agents.any { it.id == agentId }) { "Agent with ID '$agentId' not found. Ensure the director agent is added before designating." }
        this.directorAgentId = agentId
    }

    internal fun build(): KAIAgentSystem {
        val designatedDirectorId = requireNotNull(directorAgentId) { "A director agent must be designated using designateDirector()" }
        val orchestrator = Orchestrator()
        agents.forEach { orchestrator.addAgent(it) }
        val handoffManager = HandoffManager(orchestrator, toolManager)
        return KAIAgentSystem(handoffManager, designatedDirectorId, tenantManager)
    }
}


/**
 * Represents the result of starting a new conversation run.
 */
data class RunResult(
    val conversationId: String,
    val messageFlow: Flow<LLMMessage> // Still returning Flow<LLMMessage> for now
)

/**
 * A high-level facade for interacting with the KAI agent system.
 * Manages Orchestrator, HandoffManager, and Conversation lifecycle for simplified usage.
 */
class KAIAgentSystem internal constructor(
    private val handoffManager: HandoffManager,
    private val directorAgentId: String,
    private val tenantManager: TenantManager // Inject TenantManager
) {

    /**
     * Starts a new conversation with the initial user input.
     *
     * @param tenantId The ID of the tenant.
     * @param initialInput The first message from the user.
     * @param requestId The ID of the request.
     * @param sessionId The ID of the session.
     * @return A RunResult containing the new conversation ID and the flow of messages for this run.
     */
    suspend fun run(
        tenantId: String,
        initialInput: String,
        requestId: String,
        sessionId: String
    ): Either<SystemError, RunResult> {
        val tenant = tenantManager.getTenant(tenantId) 
            ?: return Either.Left(SystemError.TenantNotFound(tenantId))

        val tenantContext = TenantContext(
            tenant = tenant,
            sessionId = sessionId,
            requestId = requestId
        )

        return withTenantContext(tenantContext) {
            val conversationId = handoffManager.startConversation()
            val initialMessage = LLMMessage.UserMessage(initialInput)
            val flow = handoffManager.sendMessage(conversationId, initialMessage, directorAgentId, tenantContext)
                ?: return@withTenantContext Either.Left(SystemError.ConversationOperationFailed("Failed to send message for newly created conversation $conversationId"))
            
            Either.Right(RunResult(conversationId, flow))
        }
    }

    /**
     * Sends a subsequent message to an existing conversation.
     *
     * @param tenantId The ID of the tenant.
     * @param conversationId The ID of the conversation to continue.
     * @param input The user's follow-up message.
     * @param requestId The ID of the request.
     * @param sessionId The ID of the session.
     * @return A flow of messages generated in response to this specific input.
     */
    suspend fun run(
        tenantId: String,
        conversationId: String, 
        input: String,
        requestId: String,
        sessionId: String
    ): Either<SystemError, RunResult> {
        val tenant = tenantManager.getTenant(tenantId) 
            ?: return arrow.core.Either.Left(SystemError.TenantNotFound(tenantId))

        val tenantContext = TenantContext(
            tenant = tenant,
            sessionId = sessionId,
            requestId = requestId
        )

        return withTenantContext(tenantContext) {
            val message = LLMMessage.UserMessage(input)
            val flow = handoffManager.sendMessage(conversationId, message, directorAgentId, tenantContext)
                ?: return@withTenantContext Either.Left(SystemError.ConversationOperationFailed("Could not find conversation $conversationId to send message."))
            
            Either.Right(RunResult(conversationId, flow))
        }
    }

    companion object {
        fun build(block: KAIAgentSystemBuilder.() -> Unit): KAIAgentSystem {
            return KAIAgentSystemBuilder().apply(block).build()
        }
    }
}
