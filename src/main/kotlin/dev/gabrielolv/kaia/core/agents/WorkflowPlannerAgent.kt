package dev.gabrielolv.kaia.core.agents

import dev.gabrielolv.kaia.core.HandoffManager
import dev.gabrielolv.kaia.core.Workflow
import dev.gabrielolv.kaia.core.WorkflowPlanResponse
import dev.gabrielolv.kaia.core.WorkflowStep
import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json

/**
 * Agent extension that acts as a workflow planner.
 * It analyzes the user message and decides if a multi-step workflow
 * is needed, generating the plan if necessary.
 */
fun Agent.Companion.withWorkflowPlanner(
    // Pass the HandoffManager (soon to be WorkflowExecutor)
    handoffManager: HandoffManager,
    provider: LLMProvider,
    agentDatabase: Map<String, String> = handoffManager.orchestrator.getAgentDatabase(),
    // The default agent to use if no workflow is needed or planning fails
    defaultAgent: Agent,
    json: Json = Json { ignoreUnknownKeys = true; isLenient = true } // Lenient JSON parser
): Agent {
    // Build agent catalog string
    val agentCatalog = agentDatabase.entries.joinToString("\n") {
        "${it.key}: ${it.value}"
    }

    // Create a workflow planning prompt
    val workflowPlanPrompt = """
    You are a sophisticated AI orchestrator. Your task is to analyze the user's request and determine the best plan of action.

    You have access to the following specialized agents:
    $agentCatalog

    Based on the user's message, decide if the request can be handled by a single agent ('${defaultAgent.id}') or if it requires a sequence of actions involving multiple agents (a workflow).

    If a multi-step workflow is required:
    1. Break down the user's request into logical steps.
    2. For each step, identify the most suitable agent from the list above.
    3. Define the specific action that agent needs to perform for that step.

    Respond ONLY with a JSON object in the following format:
    {
      "requiresWorkflow": boolean, // true if multiple steps/agents are needed, false otherwise
      "workflow": [ // Include this array ONLY if requiresWorkflow is true
        { "agentId": "[ID of the agent for step 1]", "action": "[Description of action for step 1]" },
        { "agentId": "[ID of the agent for step 2]", "action": "[Description of action for step 2]" },
        // ... more steps if needed
      ],
      "reason": "[Brief explanation for your decision (workflow needed/not needed)]"
    }

    Example for a complex request:
    User: "Draft an email to the team about the project delay, find a 30min slot next week for a follow-up meeting, and send the invite."
    Response:
    {
      "requiresWorkflow": true,
      "workflow": [
        { "agentId": "email-drafter", "action": "Draft an email explaining the project delay." },
        { "agentId": "calendar-scheduler", "action": "Find a 30-minute meeting slot available for the team next week." },
        { "agentId": "meeting-inviter", "action": "Send a calendar invite for the identified slot, including the draft email content." }
      ],
      "reason": "Request involves drafting, scheduling, and sending, requiring multiple specialized agents."
    }

    Example for a simple request:
    User: "What's the weather like today?"
    Response:
    {
      "requiresWorkflow": false,
      "reason": "Request can be handled directly by the default agent '${defaultAgent.id}'."
    }

    Now, analyze the user's message.
    """

    // This agent's role is *only* to plan. Execution is handled by HandoffManager.
    return create {
        // Give this planner agent a unique ID
        id = "workflow-planner-${defaultAgent.id}"
        name = "Workflow Planner for ${defaultAgent.name}"
        description = "Decides if a workflow is needed and plans it."

        processor = { message ->
            flow {
                val planningOptions = LLMOptions(
                    systemPrompt = workflowPlanPrompt,
                    temperature = 0.1 // Low temp for deterministic planning
                )

                emit(LLMMessage.SystemMessage(content = "Planning workflow...")) // Indicate planning start

                try {
                    // 1. Call LLM to get the plan
                    val planResponseText = provider.generate(message.content, planningOptions)
                        .toList() // Collect all parts of the response
                        .filterIsInstance<LLMMessage.AssistantMessage>()
                        .last().content

                    // 2. Parse the plan
                    val planResponse = try {
                        json.decodeFromString<WorkflowPlanResponse>(planResponseText)
                    } catch (e: Exception) {
                        // Handle malformed JSON from LLM
                        emit(LLMMessage.SystemMessage(content = "Error parsing workflow plan: ${e.message}. Proceeding with default agent."))
                        null
                    }

                    if (planResponse?.requiresWorkflow == true && !planResponse.workflow.isNullOrEmpty()) {
                        // 3. Workflow is required - Create Workflow object
                        val workflowSteps = planResponse.workflow.map { stepDesc ->
                            WorkflowStep(agentId = stepDesc.agentId, action = stepDesc.action)
                        }
                        val workflow = Workflow(steps = workflowSteps)

                        emit(LLMMessage.SystemMessage(content = "Workflow planned: ${planResponse.reason ?: ""} Starting execution."))
                        val currentConversationId = message.conversationId
                        // 4. Delegate execution to HandoffManager (which now handles workflows)
                        // HandoffManager.executeWorkflow will return the flow of the entire execution
                        handoffManager.executeWorkflow(currentConversationId, workflow, message)
                            .collect(::emit) // Emit messages from the workflow execution

                    } else {
                        // 5. No workflow needed or planning failed - Use default agent
                        emit(LLMMessage.SystemMessage(content = "No workflow needed: ${planResponse?.reason ?: "Defaulting"}. Processing directly."))
                        defaultAgent.process(message).collect(::emit)
                    }

                } catch (e: Exception) {
                    // Handle errors during LLM call or processing
                    emit(LLMMessage.SystemMessage(content = "Error during planning: ${e.message}. Attempting to process with default agent."))
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