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
    var taskGoal: String? = null
    var constraints: List<String>? = null
    var useCaseSpecificInstructions: List<String>? = null
    var useCaseExamples: List<DirectorExample>? = null

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
        val constraintsSection =
            constraints?.takeIf { it.isNotEmpty() }?.joinToString("\n  - ", prefix = "\n\nConstraints:\n  - ") ?: ""
        val specificInstructionsSection = useCaseSpecificInstructions?.takeIf { it.isNotEmpty() }
            ?.joinToString("\n  - ", prefix = "\n\nSpecific Instructions:\n  - ") ?: ""

        val examplesSection = useCaseExamples?.takeIf { it.isNotEmpty() }?.joinToString(
            "\n\n---\n\n"
        ) { example ->
            """
                Example: ${example.description}
                Input Context:
                Original Request: ${example.originalRequest}
                Executed Steps (including their outcomes/results): ${example.executedSteps}
                Expected JSON Output:
                ${example.expectedOutputJson}
                """
        }?.let {
            """
                Example Scenarios (Study these to understand the reasoning process):
                $it
                """
        } ?: ""

        val directorPrompt = """
    You are a highly intelligent AI orchestrator, the "Step-by-Step Director."
    Your primary responsibility is to meticulously analyze the user's original request and the full history of *executed steps and their results*. Based on this analysis, you must determine the single most logical *next* action, identify if the request has been fully completed, OR determine if clarification from the user is essential to proceed.
    $goalSection$constraintsSection$specificInstructionsSection

    Available Specialized Agents (select the most appropriate one for the next concrete step):
    $agentCatalog
    Fallback Agent for general tasks or when no specialized agent is suitable: ${fallbackAgent.id} (ID: ${fallbackAgent.id})

    Current Conversation Context:
    Original User Request: {{original_request}}
    Previously Executed Steps (this includes the specific actions taken, the agent that performed them, and the observable results or outputs from those actions):
    {{executed_steps}}

    Follow this thought process to arrive at your decision:
    1.  **Assess Completion:**
        *   Carefully compare the "Original User Request" with the "Previously Executed Steps and their results."
        *   Is the user's goal, as stated in the original request and potentially refined by the conversation, fully achieved?
        *   If yes, set "isComplete": true. Provide a clear "reasoningTrace" explaining *why* it's complete.

    2.  **Determine Next Step (If Not Complete):**
        *   If the request is not complete, what is the most logical, singular, and actionable next step towards fulfilling the "Overall Task Goal", while adhering to "Constraints" and "Specific Instructions"?
        *   Consider the "Available Specialized Agents." Is there an agent specifically designed for this next step?
            *   If yes, select that agent. Formulate a clear "action" for it.
            *   If no specialized agent is a perfect fit, but a general action can be taken, use the "Fallback Agent" (ID: ${fallbackAgent.id}). Formulate a clear "action" for it.
        *   Set "isComplete": false, "waitForUserInput": false. The "action" should be a directive for the chosen agent.

    3.  **Identify Need for Clarification (If Not Complete and Cannot Proceed):**
        *   If you lack critical information from the user that prevents you from taking a logical next step (even with the fallback agent), you MUST ask for clarification.
        *   Determine what specific information is missing.
        *   The "action" in "nextStep" should be the *exact question you need to ask the user*.
        *   Set "isComplete": false, and crucially, set "waitForUserInput": true.
        *   The "agentId" for asking clarification can be the "Fallback Agent" or an agent designated for user interaction if available.

    Respond ONLY with a JSON object in the following structured format. Do NOT add any explanatory text outside this JSON structure:
    {
      "nextStep": { // Include ONLY if "isComplete" is false.
        "agentId": "[ID of the agent for the next step or for asking clarification]",
        "action": "[If waitForUserInput is false, this is the specific, concise action for the selected agent. If waitForUserInput is true, this is the exact, polite question to ask the user.]",
        "reason": "[Brief, one-sentence reason for choosing this specific agent and action, or for asking this specific question]"
      },
      "isComplete": [true if the original request is fully addressed and no further actions are needed, false otherwise],
      "waitForUserInput": [true if you are asking the user a question to get necessary information before proceeding, false otherwise],
      "reasoningTrace": "[A detailed step-by-step explanation of your thought process: how you evaluated completion, why you chose the next step (or decided to ask for clarification), and how it aligns with the overall goal and executed steps. Be specific.]"
    }
    $examplesSection
    """
        // --- Prompt Construction --- End

        return { message, conversation ->
            flow {
                val originalRequest = message.content

                // Filter history for the prompt, excluding tool messages
                val promptHistory = conversation.messages
                    .filterNot { it is LLMMessage.ToolCallMessage || it is LLMMessage.ToolResponseMessage }
                    .joinToString("\n") { it.asPromptString() }

                val filledPrompt = directorPrompt
                    .replace("{{original_request}}", originalRequest)
                    .replace("{{executed_steps}}", promptHistory)

                val planningOptions = LLMOptions(
                    systemPrompt = filledPrompt.trimIndent(),
                    responseFormat = "json_object",
                    temperature = 0.1
                )

                val tempMessage = LLMMessage.UserMessage("Determine next step based on executed steps.")
                var originalLlmMessage: LLMMessage.AssistantMessage? = null // Store the original message for context

                try {
                    val directorResponseText = provider
                        .generate(conversation.messages + tempMessage, planningOptions)
                        .mapNotNull { msg ->
                            (msg as? LLMMessage.AssistantMessage)?.also { originalLlmMessage = it }
                        }
                        .lastOrNull()?.content

                    if (directorResponseText != null) {
                        // Attempt to parse into the typed object
                        try {
                            val directorOutput = json.decodeFromString<DirectorOutput>(directorResponseText)
                            // Emit the structured result
                            emit(
                                StructuredResult(
                                    data = directorOutput,
                                    rawContent = directorResponseText,
                                    rawMessage = originalLlmMessage
                                )
                            )
                        } catch (e: SerializationException) {
                            // Emit an error result if parsing fails
                            emit(
                                ErrorResult(
                                    error = e,
                                    message = "Director agent response parsing failed: ${e.message}\nRaw Response: $directorResponseText\nExpected format: See prompt for JSON structure.",
                                    rawMessage = originalLlmMessage
                                )
                            )
                        } catch (e: Exception) {
                            // Emit an error result for unexpected parsing errors
                            emit(
                                ErrorResult(
                                    error = e,
                                    message = "Unexpected error during director response parsing: ${e.message}",
                                    rawMessage = originalLlmMessage
                                )
                            )
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
