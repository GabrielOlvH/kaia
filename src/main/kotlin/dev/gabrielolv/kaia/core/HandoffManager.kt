package dev.gabrielolv.kaia.core

import dev.gabrielolv.kaia.utils.nextThreadId
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages handoffs between agents
 */
class HandoffManager(val orchestrator: Orchestrator, private val handoffAgentId: String) {
    private val conversations = ConcurrentHashMap<String, Conversation>()

    /**
     * Start a new conversation with an initial agent
     */
    fun startConversation(
        conversationId: String = nextThreadId,
        initialAgentId: String
    ): String {
        assert(initialAgentId.isNotEmpty()) {  "Initial agent ID cannot be empty" }
        val initialAgent = orchestrator.getAgent(initialAgentId)
            ?: throw IllegalArgumentException("Agent $initialAgentId not found")

        conversations[conversationId] = Conversation(
            id = conversationId,
            currentAgentId = initialAgentId,
            messages = mutableListOf()
        )

        return conversationId
    }

    /**
     * Get a conversation by ID
     */
    fun getConversation(conversationId: String): Conversation? {
        return conversations[conversationId]
    }

    /**
     * Perform a handoff to another agent
     */
    fun handoff(
        conversationId: String,
        targetAgentId: String,
        reason: String
    ): Boolean {
        val conversation = conversations[conversationId]
            ?: return false

        val targetAgent = orchestrator.getAgent(targetAgentId)
            ?: return false

        // Record the handoff in the conversation
        conversation.handoffs.add(
            Handoff(
                fromAgentId = conversation.currentAgentId,
                toAgentId = targetAgentId,
                reason = reason,
                timestamp = System.currentTimeMillis()
            )
        )

        // Update the current agent
        conversation.currentAgentId = targetAgentId

        return true
    }

    /**
     * Send a message to the current agent in a conversation
     */
    suspend fun sendMessage(
        conversationId: String,
        message: Message
    ): Message? {
        assert(message.content.isNotBlank()) { "Message cannot be blank" }
        assert(message.sender.isNotBlank()) { "Message sender cannot be empty" }

        val conversation = conversations[conversationId]
            ?: return null

        val response = orchestrator.processWithAgent(handoffAgentId, message)
        conversation.messages.add(response)

        return response
    }

    /**
     * Get the conversation history
     */
    fun getHistory(conversationId: String): List<Message>? {
        return conversations[conversationId]?.messages?.toList()
    }

    /**
     * Get the handoff history
     */
    fun getHandoffs(conversationId: String): List<Handoff>? {
        return conversations[conversationId]?.handoffs?.toList()
    }
}

/**
 * Represents a conversation between a user and multiple agents
 */
data class Conversation(
    val id: String,
    var currentAgentId: String,
    val messages: MutableList<Message>,
    val handoffs: MutableList<Handoff> = mutableListOf()
)

/**
 * Represents a handoff between agents
 */
data class Handoff(
    val fromAgentId: String,
    val toAgentId: String,
    val reason: String,
    val timestamp: Long
)