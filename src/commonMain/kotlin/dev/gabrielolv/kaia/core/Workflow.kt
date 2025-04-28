package dev.gabrielolv.kaia.core

import kotlinx.serialization.Serializable


@Serializable
enum class StepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}

@Serializable
data class NextStepInfo(
    val agentId: String,
    val action: String,
    val reason: String? = null // Reason for *this specific* step
)

@Serializable
data class DirectorResponse(
    val nextStep: NextStepInfo? = null,
    val isComplete: Boolean,
    val waitForUserInput: Boolean = false,
    val overallReason: String? = null
)
