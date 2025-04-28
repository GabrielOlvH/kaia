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
    private var directorAgentId: String? = null // Added: Store the ID of the designated director

    /**
     * Adds a specialized agent to the system.
     * Use helper extensions like `addLLMAgent` or `addDatabaseAgent` for common types.
     */
    fun addAgent(agent: Agent) {
        this.agents.add(agent)
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
        // Require that a director has been designated
        val designatedDirectorId = requireNotNull(directorAgentId) { "A director agent must be designated using designateDirector()" }

        val orchestrator = Orchestrator()
        agents.forEach { orchestrator.addAgent(it) } // Add all agents first

        val handoffManager = HandoffManager(orchestrator)

        // Pass the designated director ID to the KAIAgentSystem
        return KAIAgentSystem(handoffManager, designatedDirectorId)
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
    val handoffManager: HandoffManager,
    private val directorAgentId: String // This remains the same, just sourced differently
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
