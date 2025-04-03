package dev.gabrielolv.kaia.core

import kotlinx.serialization.Serializable

@Serializable
data class WorkflowStep(
    val agentId: String,
    val action: String, // Description of the task for the agent
    var status: StepStatus = StepStatus.PENDING,
    var error: String? = null
)

@Serializable
data class Workflow(
    val steps: List<WorkflowStep>
)

@Serializable
enum class StepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED // If a previous step failed
}

// Helper structure for the planning LLM call
@Serializable
data class WorkflowPlanResponse(
    val workflow: List<WorkflowStepDescription>, // Use a simpler structure for generation
    val reason: String? = null // Reason why a workflow is/isn't needed
)

@Serializable
data class WorkflowStepDescription(
    val agentId: String,
    val action: String
)

@Serializable
data class NextStepInfo(
    val agentId: String,
    val action: String,
    val reason: String? = null // Reason for *this specific* step
)

@Serializable
data class DirectorResponse(
    // If not null, this is the next step to execute
    val nextStep: NextStepInfo? = null,
    // Indicates if the overall goal is considered achieved
    val isComplete: Boolean,
    // Overall reason for completion or choosing the next step
    val overallReason: String? = null
)
