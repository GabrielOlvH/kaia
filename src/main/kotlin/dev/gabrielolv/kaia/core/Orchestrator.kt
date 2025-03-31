package dev.gabrielolv.kaia.core

import dev.gabrielolv.kaia.core.agents.Agent
import dev.gabrielolv.kaia.llm.LLMMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Orchestrates the interaction between multiple agents
 */
class Orchestrator(
    private val agents: MutableMap<String, Agent> = mutableMapOf(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    /**
     * Add an agent to the orchestrator
     */
    fun addAgent(agent: Agent) {
        agents[agent.id] = agent
    }

    /**
     * Remove an agent from the orchestrator
     */
    fun removeAgent(agentId: String) {
        agents.remove(agentId)
    }

    fun getAgentDatabase(): Map<String, String> {
        return agents.mapKeys { it.key }.mapValues { it.value.description }
    }

    /**
     * Get an agent by ID
     */
    fun getAgent(agentId: String): Agent? = agents[agentId]

    suspend fun processWithAgent(
        agentId: String,
        message: Message,
        conversation: Conversation // Added conversation
    ): Flow<LLMMessage> {
        val agent = agents[agentId] ?: throw IllegalArgumentException("Agent $agentId not found")
        // Pass conversation to agent.process
        return agent.process(message, conversation)
    }


    /**
     * Send a message to multiple agents and collect their responses
     */
    fun broadcast(
        conversation: Conversation, // Pass conversation directly
        message: Message,
        agentIds: List<String>
    ): Flow<LLMMessage> = flow {
        agentIds.map { agentId ->
            scope.async {
                try {
                    val agent = agents[agentId] ?: throw IllegalArgumentException("Agent $agentId not found")
                    // Pass conversation to agent.process
                    agent.process(message.copy(recipient = agentId), conversation).collect { emit(it) }
                } catch (e: Exception) {
                    emit(
                        LLMMessage.SystemMessage(
                            content = "Error processing broadcast message by agent $agentId: ${e.message}"
                        )
                    )
                }
            }
        }
    }
}