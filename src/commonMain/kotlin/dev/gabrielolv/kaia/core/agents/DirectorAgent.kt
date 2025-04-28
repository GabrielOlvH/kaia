package dev.gabrielolv.kaia.core.agents

import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.mapNotNull

fun Agent.Companion.withDirectorAgent(
    provider: LLMProvider,
    agentDatabase: Map<String, String>,
    fallbackAgent: Agent,
    block: AgentBuilder.() -> Unit
): Agent {
    val agentCatalog = agentDatabase.entries.joinToString("\n") {
        "${it.key}: ${it.value}"
    }

    val directorPrompt = """
    You are a sophisticated AI orchestrator acting as a step-by-step director.
    Your task is to analyze the user's original request and the executed steps to determine the single best *next* action, or if the request is complete, OR if you need clarification from the user.

    Available specialized agents:
    $agentCatalog
    Default Agent ID for general tasks: ${fallbackAgent.id}

    Original User Request: {{original_request}}
    
    Previously Executed Steps:
    {{executed_steps}}

    Based on the original request and the executed steps:
    1. Evaluate if the original request is fully addressed. If yes, set "isComplete": true.
    2. Evaluate if you have enough information to proceed with the *next logical step*.
       - If yes, determine the single best agent and action for that step. Set "isComplete": false, "waitForUserInput": false.
    3. Evaluate if you are missing crucial information from the user to proceed.
       - If yes, determine the agent/action needed to *ask the user for clarification*. Set "isComplete": false, and crucially, set "waitForUserInput": true.

    Respond ONLY with a JSON object in the following format:
    {
      "nextStep": { // Include ONLY if isComplete is false. Contains the action (either progressing or asking clarification).
        "agentId": "[ID of the agent for the next step/clarification]",
        "action": "[Specific action OR the question to ask the user]",
        "reason": "[Brief reason for this step/question]"
      },
      "isComplete": [true if the original request is fully addressed, false otherwise],
      "waitForUserInput": [true if the previous step required more information to be completed, false otherwise],
      "overallReason": "[Explanation for completion, next step, or why clarification is needed]"
    }

    Example (Needs Clarification):
    History: User: "Book a flight to Paris." Assistant: (Previous step asked 'Which dates?')
    Original Request: "Book a flight to Paris."
    Response:
    {
      "nextStep": {
        "agentId": "${fallbackAgent.id}", // Or a specific 'user-interaction' agent
        "action": "Ask the user for their desired departure and return dates for the Paris flight.",
        "reason": "Cannot book flight without dates."
      },
      "isComplete": false,
      "waitForUserInput": true, // Signal to pause after asking
      "overallReason": "Missing necessary date information from the user to proceed with booking."
    }

    Example (Proceeding after Clarification):
    History: User: "Book flight to Paris." Assistant: "Which dates?" User: "Next Tuesday to Friday."
    Original Request: "Book a flight to Paris."
    Response:
    {
      "nextStep": {
        "agentId": "flight-booker",
        "action": "Find flight options to Paris for next Tuesday to Friday.",
        "reason": "User provided dates, now search for flights."
      },
      "isComplete": false,
      "waitForUserInput": false,
      "overallReason": "Proceeding with flight search using provided dates."
    }

    Now, analyze the current state and decide the next action, completion, or if clarification is needed.
    """

    val builder = AgentBuilder().apply(block)

    builder.id.ifBlank { builder.id = "director-agent" }
    builder.name.ifBlank { builder.name = "Step-by-Step Director" }
    builder.description.ifBlank { builder.description = "Determines the next best step or completion status for a request." }

    builder.processor = { message, conversation ->
        flow {
            val originalRequest = conversation.originalUserRequest

            val formattedExecutedSteps = if (conversation.executedSteps.isEmpty()) {
                "No steps executed yet."
            } else {
                conversation.executedSteps.mapIndexed { index, step ->
                    val stepHeader = "Step ${index+1}: Agent: ${step.agentId}, Action: '${step.action}', Status: ${step.status}" +
                    (step.error?.let { ", Error: $it" } ?: "")
                    
                    val messageContent = if (step.messages.isNotEmpty()) {
                        "\nAgent ${step.agentId} messages in this step:\n" + step.messages.filterIsInstance<LLMMessage.SystemMessage>()
                            .joinToString("\n") { message -> message.content }
                    } else {
                        "\nNo messages recorded for this step."
                    }
                    
                    "$stepHeader$messageContent\n"
                }.joinToString("\n")
            }

            val filledPrompt = directorPrompt
                .replace("{{original_request}}", originalRequest)
                .replace("{{executed_steps}}", formattedExecutedSteps)

            val planningOptions = LLMOptions(
                systemPrompt = filledPrompt,
                responseFormat = "json_object",
                temperature = 0.1
            )

            val tempMessage = LLMMessage.UserMessage("Determine next step based on executed steps.")
            val tempMessages = conversation.messages.toMutableList().apply { add(tempMessage) }

            try {
                val directorResponseText = provider
                    .generate(tempMessages, planningOptions)
                    .mapNotNull { it as? LLMMessage.AssistantMessage }
                    .lastOrNull()?.content

                if (directorResponseText != null) {
                    emit(LLMMessage.AssistantMessage(content = directorResponseText))
                } else {
                    emit(LLMMessage.SystemMessage(content = "Director agent failed to generate a response."))
                }
            } catch (e: Exception) {
                emit(LLMMessage.SystemMessage(content = "Error calling Director agent: ${e.message}"))
            }
        }
    }
    return builder.build()
}
