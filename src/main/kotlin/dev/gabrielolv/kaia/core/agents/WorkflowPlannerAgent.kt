package dev.gabrielolv.kaia.core.agents

import dev.gabrielolv.kaia.core.HandoffManager
import dev.gabrielolv.kaia.core.Workflow
import dev.gabrielolv.kaia.core.WorkflowPlanResponse // Make sure this is the updated version
import dev.gabrielolv.kaia.core.WorkflowStep
import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json

// Assuming WorkflowPlanResponse and WorkflowStepDescription are defined as above

fun Agent.Companion.withWorkflowPlanner(
    handoffManager: HandoffManager,
    provider: LLMProvider,
    agentDatabase: Map<String, String> = handoffManager.orchestrator.getAgentDatabase(),
    defaultAgent: Agent,
    json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
): Agent {
    // Build agent catalog string
    val agentCatalog = agentDatabase.entries.joinToString("\n") {
        "${it.key}: ${it.value}"
    }

    // Create a workflow planning prompt
    val workflowPlanPrompt = """
    You are a sophisticated AI orchestrator. Your task is to analyze the user's request and create an execution plan.

    You have access to the following specialized agents:
    $agentCatalog

    Based on the user's message:
    1. Break down the user's request into one or more logical steps.
    2. For each step, identify the most suitable agent from the list above (including '${defaultAgent.id}' if no specific agent fits).
    3. Define the specific action that agent needs to perform for that step.

    Respond ONLY with a JSON object in the following format:
    {
      "workflow": [
        { "agentId": "[ID of the agent for step 1]", "action": "[Description of action for step 1]" },
        // ... more steps if needed (minimum of one step)
      ],
      "reason": "[Brief explanation for the chosen agent(s) and steps]"
    }

    Example for a multi-step request:
    User: "Draft an email to the team about the project delay, find a 30min slot next week for a follow-up meeting, and send the invite."
    Response:
    {
      "workflow": [
        { "agentId": "email-drafter", "action": "Draft an email explaining the project delay." },
        { "agentId": "calendar-scheduler", "action": "Find a 30-minute meeting slot available for the team next week." },
        { "agentId": "meeting-inviter", "action": "Send a calendar invite for the identified slot, including the draft email content." }
      ],
      "reason": "Request involves drafting, scheduling, and sending, requiring multiple specialized agents."
    }

    Example for a single-step request:
    User: "What's the weather like today?"
    Response:
    {
      "workflow": [
        { "agentId": "weather-checker", "action": "Get the current weather conditions." }
        // Or potentially: { "agentId": "${defaultAgent.id}", "action": "Answer the user's question about the weather." }
      ],
      "reason": "Request is a direct query best handled by the weather agent."
    }

    Now, analyze the user's message and generate the execution plan.
    """


    return create {
        id = "workflow-planner-${defaultAgent.id}"
        name = "Workflow Planner for ${defaultAgent.name}"
        description = "Analyzes requests and creates an execution plan."

        processor = { message ->
            flow {
                val planningOptions = LLMOptions(
                    systemPrompt = workflowPlanPrompt,
                    responseFormat = "json_object",
                    temperature = 0.1
                )

                emit(LLMMessage.SystemMessage(content = "Planning execution..."))

                try {
                    // 1. Call LLM to get the plan
                    val planResponseText = provider.generate(message.content, planningOptions)
                        .toList()
                        .filterIsInstance<LLMMessage.AssistantMessage>()
                        .lastOrNull()?.content // Use lastOrNull for safety

                    // 2. Parse the plan
                    val planResponse: WorkflowPlanResponse? = planResponseText?.let { text ->
                        try {
                            json.decodeFromString<WorkflowPlanResponse>(text)
                        } catch (e: Exception) {
                            emit(LLMMessage.SystemMessage(content = "Error parsing execution plan: ${e.message}. Proceeding with default agent."))
                            null // Parsing failed
                        }
                    }

                    // 3. Check if plan is valid and has steps
                    if (planResponse != null && planResponse.workflow.isNotEmpty()) {
                        // 4. Plan is valid - Create Workflow object
                        val workflowSteps = planResponse.workflow.map { stepDesc ->
                            WorkflowStep(agentId = stepDesc.agentId, action = stepDesc.action)
                        }
                        val workflow = Workflow(steps = workflowSteps)
                        val planType = if (workflow.steps.size > 1) "Multi-step workflow" else "Single-step plan"

                        emit(LLMMessage.SystemMessage(content = "$planType generated: ${planResponse.reason ?: ""} Starting execution."))
                        val currentConversationId = message.conversationId
                        // 5. Delegate execution to HandoffManager
                        handoffManager.executeWorkflow(currentConversationId, workflow, message)
                            .collect(::emit) // Emit messages from the workflow execution

                    } else {
                        // 6. Planning failed, plan was empty, or LLM response was missing
                        val reason = when {
                            planResponseText == null -> "LLM did not provide a response."
                            planResponse == null -> "Plan parsing failed." // Already emitted specific error
                            planResponse.workflow.isEmpty() -> "LLM returned an empty workflow."
                            else -> "Unknown planning issue." // Should not happen
                        }
                        emit(LLMMessage.SystemMessage(content = "Planning failed ($reason). Processing with default agent."))
                        defaultAgent.process(message).collect(::emit)
                    }

                } catch (e: Exception) {
                    // Handle errors during LLM call or general processing
                    emit(LLMMessage.SystemMessage(content = "Error during planning/execution: ${e.message}. Attempting to process with default agent."))
                    try {
                        defaultAgent.process(message).collect(::emit)
                    } catch (e2: Exception) {
                        emit(LLMMessage.SystemMessage(content = "Failed to process with default agent after planning error: ${e2.message}"))
                    }
                }
            }
        }
    }
}
