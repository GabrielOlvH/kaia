package dev.gabrielolv.kaia.core

import dev.gabrielolv.kaia.core.agents.Agent
import dev.gabrielolv.kaia.llm.LLMMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Builder for configuring and creating a KAIAgentSystem.
 */
class KAIAgentSystemBuilder internal constructor() { // Make constructor internal
    internal val agents: MutableList<Agent> = mutableListOf() // Keep internal for helpers
    private var directorAgentFactory: ((agentDatabase: Map<String, String>) -> Agent)? = null

    /**
     * Adds a specialized agent to the system.
     * Use helper extensions like `addLLMAgent` or `addDatabaseAgent` for common types.
     */
    fun addAgent(agent: Agent) {
        this.agents.add(agent)
    }

    /**
     * Sets the factory function responsible for creating the DirectorAgent.
     * The factory lambda receives the automatically generated agent database
     * (containing agents added via `addAgent` or helpers) and must return
     * a fully configured DirectorAgent instance.
     *
     * Example:
     * ```kotlin
     * setDirectorAgentFactory { db ->
     *     Agent.withDirectorAgent {
     *         provider = myDirectorProvider
     *         fallbackAgent = myFallbackAgent // Must be defined outside
     *         agentDatabase = db // Use the provided database
     *         // ... other custom director config
     *     }
     * }
     * ```
     * This factory is required.
     */
    fun setDirectorAgentFactory(factory: (agentDatabase: Map<String, String>) -> Agent) {
        this.directorAgentFactory = factory
    }

    internal fun build(): KAIAgentSystem {
        val factory = requireNotNull(directorAgentFactory) { "Director agent factory must be set using setDirectorAgentFactory()" }

        val orchestrator = Orchestrator()
        agents.forEach { orchestrator.addAgent(it) }

        val agentDatabase = orchestrator.getAgentDatabase()

        val directorAgent = factory(agentDatabase)

        orchestrator.addAgent(directorAgent)

        val handoffManager = HandoffManager(orchestrator)

        return KAIAgentSystem(handoffManager, directorAgent.id)
    }
}

/**
 * Represents the result of starting a new conversation run.
 */
data class RunResult(
    val conversationId: String,
    val messageFlow: Flow<LLMMessage>
)

/**
 * A high-level facade for interacting with the KAI agent system.
 * Manages Orchestrator, HandoffManager, and Conversation lifecycle for simplified usage.
 */
class KAIAgentSystem internal constructor(
    private val handoffManager: HandoffManager,
    private val directorAgentId: String
) {

    /**
     * Starts a new conversation with the initial user input.
     *
     * @param initialInput The first message from the user.
     * @return A RunResult containing the new conversation ID and the flow of messages for this run.
     */
    suspend fun run(initialInput: String): RunResult {
        val conversationId = handoffManager.startConversation(initialMessageContent = initialInput)
        val initialMessage = LLMMessage.UserMessage(initialInput)
        val flow = handoffManager.sendMessage(conversationId, initialMessage, directorAgentId)
            ?: run {
                println("Error: Failed to send message for newly created conversation $conversationId")
                emptyFlow()
            }
        return RunResult(conversationId, flow)
    }

    /**
     * Sends a subsequent message to an existing conversation.
     *
     * @param conversationId The ID of the conversation to continue.
     * @param input The user's follow-up message.
     * @return A flow of messages generated in response to this specific input.
     */
    suspend fun run(conversationId: String, input: String): Flow<LLMMessage> {
        val message = LLMMessage.UserMessage(input)
        return handoffManager.sendMessage(conversationId, message, directorAgentId)
            ?: run {
                println("Error: Could not find conversation $conversationId to send message.")
                emptyFlow()
            }
    }

    companion object {
        fun build(block: KAIAgentSystemBuilder.() -> Unit): KAIAgentSystem {
            // Make constructor internal so build is the only entry point
            return KAIAgentSystemBuilder().apply(block).build()
        }
    }
}
