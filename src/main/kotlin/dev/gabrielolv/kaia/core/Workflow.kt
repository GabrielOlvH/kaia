package dev.gabrielolv.kaia.core

import kotlinx.serialization.Serializable

@Serializable
data class WorkflowStep(
    val agentId: String,
    val action: String, // Description of the task for the agent
    var status: StepStatus = StepStatus.PENDING,
    var resultSummary: String? = null, // Optional summary of the step's result
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
    val requiresWorkflow: Boolean,
    val workflow: List<WorkflowStepDescription>? = null, // Use a simpler structure for generation
    val reason: String? = null // Reason why a workflow is/isn't needed
)

@Serializable
data class WorkflowStepDescription(
    val agentId: String,
    val action: String
)
