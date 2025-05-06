package dev.gabrielolv.kaia.core.model // Updated package

import kotlinx.serialization.Serializable

/**
 * Represents the structured output of the DirectorAgent.
 */
@Serializable
data class DirectorOutput(
    val nextStep: NextStep? = null, // Nullable if isComplete is true
    val isComplete: Boolean,
    val waitForUserInput: Boolean,
    val overallReason: String
)

/**
 * Represents the details of the next step determined by the DirectorAgent.
 */
@Serializable
data class NextStep(
    val agentId: String,
    val action: String, // Can be an action description or a question to the user
    val reason: String
)
