package dev.gabrielolv.kaia.core.agents

import dev.gabrielolv.kaia.core.Conversation
import dev.gabrielolv.kaia.core.model.*
import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

// Added: Data class for structured examples
data class DirectorExample(
    val description: String, // Describe the scenario
    val originalRequest: String,
    val executedSteps: String, // Simplified representation of history
    val expectedOutputJson: String // The JSON the director should produce
)

fun Agent.Companion.director(block: DirectorAgentBuilder.() -> Unit): Agent {
    val builder = DirectorAgentBuilder().apply(block)

    builder.id.ifBlank { builder.id = "director-agent" }
    builder.name.ifBlank { builder.name = "Step-by-Step Director" }
    builder.description.ifBlank { builder.description = "Determines the next best step or completion status for a request." }

    builder.processor = builder.buildProcessor()

    return builder.build()
}

// Define the builder class
class DirectorAgentBuilder : AgentBuilder() {
    var provider: LLMProvider? = null
    var agentDatabase: Map<String, String>? = null
    var fallbackAgent: Agent? = null
    var taskGoal: String? = null // Added: Overall goal
    var constraints: List<String>? = null // Added: List of constraints
    var useCaseSpecificInstructions: List<String>? = null // Added: Specific instructions
    var useCaseExamples: List<DirectorExample>? = null // Added: Structured examples

    // Configure JSON parser
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Define the buildProcessor extension function - Updated return type
    fun buildProcessor(): (LLMMessage.UserMessage, Conversation) -> Flow<AgentResult> {
        requireNotNull(provider) { "LLMProvider must be set for DirectorAgent" }
        requireNotNull(agentDatabase) { "Agent database must be set for DirectorAgent" }
        requireNotNull(fallbackAgent) { "Fallback agent must be set for DirectorAgent" }

        val provider = provider!!
        val agentDatabase = agentDatabase!!
        val fallbackAgent = fallbackAgent!!

        val agentCatalog = agentDatabase.entries.joinToString("\n") {
            "${it.key}: ${it.value}"
        }

        // --- Prompt Construction --- Start
        val goalSection = taskGoal?.let { "\n\nOverall Task Goal: $it" } ?: ""
        val constraintsSection = constraints?.takeIf { it.isNotEmpty() }?.joinToString("\n  - ", prefix = "\n\nConstraints:\n  - ") ?: ""
        val specificInstructionsSection = useCaseSpecificInstructions?.takeIf { it.isNotEmpty() }?.joinToString("\n  - ", prefix = "\n\nSpecific Instructions:\n  - ") ?: ""

        val examplesSection = useCaseExamples?.takeIf { it.isNotEmpty() }?.joinToString("\n\n---\n") { example ->
            "Example: ${example.description}\nInput Context:\n  Original Request: ${example.originalRequest}\n  Executed Steps: ${example.executedSteps}\nExpected JSON Output:\n${example.expectedOutputJson}"
        }?.let { "\n\nExamples:\n$it" } ?: ""

        val directorPrompt = """
    You are a sophisticated AI orchestrator acting as a step-by-step director.
    Your primary task is to analyze the user's original request and the *results* of previously executed steps to determine the single best *next* action, or if the request is complete, OR if you need clarification from the user.
    $goalSection$constraintsSection$specificInstructionsSection

    Available specialized agents:
    $agentCatalog
    Default Agent ID for general tasks: ${fallbackAgent.id}

    Current Conversation Context:
    Original User Request: {{original_request}}
    Previously Executed Steps (including agent output/results):
    {{executed_steps}}

    Based on the original request and the executed steps:
    1. Evaluate if the original request is fully addressed by the results of the executed steps. If yes, set "isComplete": true.
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
    $examplesSection
    """
        // --- Prompt Construction --- End

        return { message, conversation ->
            flow {
                val originalRequest = conversation.originalUserRequest

                // Filter history for the prompt, excluding tool messages
                val promptHistory = conversation.messages
                    .filterNot { it is LLMMessage.ToolCallMessage || it is LLMMessage.ToolResponseMessage }
                    .joinToString("\n") { it.asPromptString() }

                val filledPrompt = directorPrompt
                    .replace("{{original_request}}", originalRequest)
                    .replace("{{executed_steps}}", promptHistory)

                val planningOptions = LLMOptions(
                    systemPrompt = filledPrompt,
                    responseFormat = "json_object",
                    temperature = 0.1
                )

                val tempMessage = LLMMessage.UserMessage("Determine next step based on executed steps.")
                var originalLlmMessage: LLMMessage.AssistantMessage? = null // Store the original message for context

                try {
                    val directorResponseText = provider
                        .generate(conversation.messages + tempMessage, planningOptions) // Use conversation messages directly
                        .mapNotNull { msg ->
                            (msg as? LLMMessage.AssistantMessage)?.also { originalLlmMessage = it } // Capture the assistant message
                        }
                        .lastOrNull()?.content

                    if (directorResponseText != null) {
                        // Attempt to parse into the typed object
                        try {
                            val directorOutput = json.decodeFromString<DirectorOutput>(directorResponseText)
                            // Emit the structured result
                            emit(StructuredResult(data = directorOutput, rawContent = directorResponseText, rawMessage = originalLlmMessage))
                        } catch (e: SerializationException) {
                            // Emit an error result if parsing fails
                            emit(ErrorResult(
                                error = e,
                                message = "Director agent response parsing failed: ${e.message}\nRaw Response: $directorResponseText",
                                rawMessage = originalLlmMessage
                            ))
                        } catch (e: Exception) {
                            // Emit an error result for unexpected parsing errors
                            emit(ErrorResult(
                                error = e,
                                message = "Unexpected error during director response parsing: ${e.message}",
                                rawMessage = originalLlmMessage
                            ))
                        }
                    } else {
                        // Emit a system result if LLM failed to generate
                        emit(SystemResult(message = "Director agent failed to generate a response."))
                    }
                } catch (e: Exception) {
                    // Emit an error result for errors during LLM call
                    emit(ErrorResult(error = e, message = "Error calling Director agent: ${e.message}"))
                }
            }
        }
    }
}
