package dev.gabrielolv.kaia.core

import dev.gabrielolv.kaia.llm.LLMMessage

/**
 * Represents a conversation, potentially involving a multi-step workflow.
 */
data class Conversation(
    val id: String,
    val messages: MutableList<LLMMessage> = mutableListOf(),
    // Store the currently active workflow, if any
    var currentWorkflow: Workflow? = null,
    // Track the index of the step being executed or last executed
    var currentStepIndex: Int = -1,
    // Keep handoffs for historical/audit purposes if needed
    val handoffs: MutableList<Handoff> = mutableListOf()
)

// Handoff class remains the same for now
data class Handoff(
    val fromAgentId: String,
    val toAgentId: String,
    val reason: String,
    val timestamp: Long
)
