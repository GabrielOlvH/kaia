package dev.gabrielolv.kaia.core

import dev.gabrielolv.kaia.llm.LLMMessage


// Simple structure to track executed steps if needed
data class ExecutedStep(
    val agentId: String,
    val action: String,
    var status: StepStatus = StepStatus.PENDING,
    var error: String? = null,
    val resultSummary: String? = null // Optional summary of step result
)

// Modify Conversation to hold original request and executed steps
data class Conversation(
    val id: String,
    val messages: MutableList<LLMMessage> = mutableListOf(),
    var originalUserRequest: String? = null, // Store the initial request
    val executedSteps: MutableList<ExecutedStep> = mutableListOf(), // Track history
    // Remove workflow/step index if they existed
    // var currentWorkflow: Workflow? = null,
    // var currentStepIndex: Int = 0,
    val handoffs: MutableList<Handoff> = mutableListOf() // Keep handoffs if used elsewhere
) {
    fun append(message: LLMMessage) {
        messages.add(message)
    }
}

// Handoff class remains the same for now
data class Handoff(
    val fromAgentId: String,
    val toAgentId: String,
    val reason: String,
    val timestamp: Long
)
