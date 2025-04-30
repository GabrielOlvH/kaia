package dev.gabrielolv.kaia.core

import dev.gabrielolv.kaia.llm.LLMMessage

data class ExecutedStep(
    val agentId: String,
    val action: String,
    var status: StepStatus = StepStatus.PENDING,
    var error: String? = null,
    val messages: MutableList<LLMMessage> = mutableListOf()
)

data class Conversation(
    val id: String,
    val messages: MutableList<LLMMessage> = mutableListOf(),
    var originalUserRequest: String,
    val executedSteps: MutableList<ExecutedStep> = mutableListOf(),
    val handoffs: MutableList<Handoff> = mutableListOf()
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

