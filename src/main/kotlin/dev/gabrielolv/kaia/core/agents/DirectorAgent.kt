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
    Your task is to analyze the user's original request and the conversation history (including the results of previous steps) to determine the single best *next* action required, or to determine if the request is fully completed.

    Available specialized agents:
    $agentCatalog
    Default Agent ID for general tasks: ${fallbackAgent.id}

    Original User Request: {{original_request}}

    Based on the original request and the history:
    1. Evaluate if the original request has been fully addressed by the history.
    2. If yes, respond that the task is complete.
    3. If no, determine the single most logical *next* step to progress towards fulfilling the original request.
    4. Identify the best agent (from the catalog or the default agent) to perform this single step.
    5. Define the specific action for that agent.

    Respond ONLY with a JSON object in the following format:
    {
      "nextStep": { // Include this object ONLY if another step is needed
        "agentId": "[ID of the agent for the next step]",
        "action": "[Specific action for the agent]",
        "reason": "[Brief reason for choosing this agent/action]"
      },
      "isComplete": [true if the original request is fully addressed, false otherwise],
      "overallReason": "[Brief explanation for why the task is complete or why the next step is chosen]"
    }

    Example (Mid-Execution):
    History: User asked to draft email and find meeting time. Email draft generated.
    Original Request: "Draft an email about project delay, find 30min slot next week, send invite."
    Response:
    {
      "nextStep": {
        "agentId": "calendar-scheduler",
        "action": "Find a 30-minute meeting slot available for the team next week, based on the project delay context.",
        "reason": "Email is drafted, next logical step is finding the meeting time."
      },
      "isComplete": false,
      "overallReason": "The scheduling part of the request is pending."
    }

    Example (Completion):
    History: User asked for weather. Weather agent provided forecast.
    Original Request: "What's the weather like?"
    Response:
    {
      "nextStep": null,
      "isComplete": true,
      "overallReason": "The weather forecast has been provided as requested."
    }

    Now, analyze the current state and decide the next action or completion.
    """

    val builder = AgentBuilder().apply(block)

    builder.id.ifBlank { builder.id = "director-agent" }
    builder.name.ifBlank { builder.name = "Step-by-Step Director" }
    builder.description.ifBlank { builder.description = "Determines the next best step or completion status for a request." }

    builder.processor = { message, conversation ->
        flow {
            val originalRequest = conversation.messages
                .filterIsInstance<LLMMessage.UserMessage>()
                .firstOrNull()?.content ?: message.content // Fallback

            val filledPrompt = directorPrompt
                .replace("{{original_request}}", originalRequest)

            val planningOptions = LLMOptions(
                systemPrompt = filledPrompt,
                responseFormat = "json_object",
                temperature = 0.1,
                historySize = 20
            )

            conversation.messages.add(LLMMessage.UserMessage("Determine next step."))


            try {
                val directorResponseText = provider
                    .generate(conversation.messages, planningOptions)
                    .mapNotNull { it as? LLMMessage.AssistantMessage }
                    .lastOrNull()?.content

                if (directorResponseText != null) {
                    emit(LLMMessage.AssistantMessage(content = directorResponseText,))
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