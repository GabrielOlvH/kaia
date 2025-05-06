package dev.gabrielolv.kaia.core

import dev.gabrielolv.kaia.core.agents.Agent
import dev.gabrielolv.kaia.core.model.AgentResult
import dev.gabrielolv.kaia.core.model.ErrorResult
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
        message: LLMMessage.UserMessage,
        conversation: Conversation // Added conversation
    ): Flow<AgentResult> {
        val agent = agents[agentId] ?: throw IllegalArgumentException("Agent $agentId not found")
        return agent.process(message, conversation)
    }


    /**
     * Send a message to multiple agents and collect their responses
     */
    fun broadcast(
        conversation: Conversation, // Pass conversation directly
        message: LLMMessage.UserMessage,
        agentIds: List<String>
    ): Flow<AgentResult> = flow {
        agentIds.map { agentId ->
            scope.async {
                try {
                    val agent = agents[agentId] ?: throw IllegalArgumentException("Agent $agentId not found")
                    agent.process(message.copy(), conversation).collect { emit(it) }
                } catch (e: Exception) {
                    emit(
                        ErrorResult(e)
                    )
                }
            }
        }
    }
}