package dev.gabrielolv.kaia.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    /**
     * Process a message through a specific agent
     */
    suspend fun processWithAgent(agentId: String, message: Message): Message {
        val agent = agents[agentId] ?: throw IllegalArgumentException("Agent $agentId not found")
        return agent.process(message)
    }

    /**
     * Send a message to multiple agents and collect their responses
     */
    fun broadcast(message: Message, agentIds: List<String>): Flow<Message> = flow {
        val responses = agentIds.map { agentId ->
            scope.async {
                try {
                    val agent = agents[agentId] ?: throw IllegalArgumentException("Agent $agentId not found")
                    agent.process(message.copy(recipient = agentId))
                } catch (e: Exception) {
                    Message(
                        sender = "system",
                        recipient = "orchestrator",
                        content = "Error processing message by agent $agentId: ${e.message}"
                    )
                }
            }
        }

        responses.awaitAll().forEach { emit(it) }
    }
}