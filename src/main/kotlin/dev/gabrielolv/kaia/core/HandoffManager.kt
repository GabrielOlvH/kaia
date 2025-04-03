package dev.gabrielolv.kaia.core

import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.utils.nextThreadId
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

class HandoffManager(
    val orchestrator: Orchestrator,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {
    private val conversations = ConcurrentHashMap<String, Conversation>()
    private val conversationLocks = ConcurrentHashMap<String, Mutex>()
    private val maxSteps = 10
    /**
     * Start a new conversation. The initial agent is now the planner.
     */
    fun startConversation(
        conversationId: String = nextThreadId,
        initialMessageContent: String
    ): String {
        val conversation = Conversation(
            id = conversationId,
            originalUserRequest = initialMessageContent
        )
        conversations[conversationId] = conversation
        conversationLocks.putIfAbsent(conversationId, Mutex())
        return conversationId
    }

    fun getConversation(conversationId: String): Conversation? {
        return conversations[conversationId]
    }

    /**
     * Send a message to the conversation. This will trigger the planner agent first.
     */
    suspend fun sendMessage(
        conversationId: String,
        message: Message,
        directorAgentId: String, // ID of the agent created by withDirectorAgent
    ): Flow<LLMMessage>? {
        val conversation = conversations[conversationId]
            ?: return null // Or throw exception
        val lock = conversationLocks.computeIfAbsent(conversationId) { Mutex() }

        // Ensure original request is set if this is the very first user message
        if (conversation.messages.none { it is LLMMessage.UserMessage }) {
            conversation.originalUserRequest = message.content
        }

        return channelFlow {
            lock.withLock {
                // Add user message to history
                val userMessage = LLMMessage.UserMessage(content = message.content)
                conversation.append(userMessage)
                // Don't emit the user message here, let the flow handle it if needed

                try {
                    manageStepByStepExecution(
                        conversationId,
                        directorAgentId,
                        message, // Pass the current trigger message
                        this // Pass the channelFlow scope
                    )
                } catch (e: Exception) {
                    val errorMsg = LLMMessage.SystemMessage(
                        content = "Step-by-step execution failed: ${e.message}"
                    )
                    send(errorMsg)
                    conversation.append(errorMsg)
                } finally {
                    // Check final status after loop finishes or breaks
                    val lastExecuted = conversation.executedSteps.lastOrNull()
                    if (lastExecuted?.status == StepStatus.COMPLETED && conversation.executedSteps.size > 0) {
                        // Check if the *director* decided it was complete in the last successful cycle
                        // This logic needs refinement based on how completion is signaled.
                        // For now, just signal end of process.
                        send(LLMMessage.SystemMessage("Processing complete."))
                    } else if (conversation.executedSteps.any { it.status == StepStatus.FAILED }) {
                        send(LLMMessage.SystemMessage("Processing finished with errors."))
                    } else if (conversation.executedSteps.size >= maxSteps) {
                        send(LLMMessage.SystemMessage("Processing stopped: Maximum step limit reached."))
                    }
                    // Optionally add a final "Workflow complete" or "Workflow failed" message
                }
            }
        }.onEach { llmMessage ->
            // Ensure all messages emitted by the flow are added to history
            // Avoid double-adding the initial user message
            if (conversation.messages.lastOrNull() != llmMessage) {
                conversation.append(llmMessage)
            }
        }
    }


    private suspend fun manageStepByStepExecution(
        conversationId: String,
        directorAgentId: String,
        triggerMessage: Message, // The message that initiated this cycle
        scope: ProducerScope<LLMMessage>
    ) {
        val conversation = conversations[conversationId] ?: return
        val directorAgent = orchestrator.getAgent(directorAgentId) ?: run {
            scope.send(LLMMessage.SystemMessage("Error: Director agent '$directorAgentId' not found."))
            return
        }

        var currentStep = 1
        while (currentStep <= maxSteps) {
            scope.send(LLMMessage.SystemMessage("Director: Deciding next step ($currentStep/$maxSteps)..."))

            // 1. Call Director Agent
            var directorResponse: DirectorResponse? = null
            var directorFailed = false
            var lastAssistantMessageContent: String? = null
            try {
                val directorTrigger = triggerMessage.copy(
                    content = "Based on the history and original request, determine the next step or completion.",
                    recipient = directorAgentId
                )

                directorAgent.process(directorTrigger, conversation)
                    .mapNotNull { msg ->
                        // Look for the specific assistant message marked as director response
                        if (msg is LLMMessage.AssistantMessage ) {
                            lastAssistantMessageContent = msg.content
                        } else {
                            scope.send(msg)
                            null
                        }
                    }
                    .lastOrNull() // Expecting one final JSON response

                if (lastAssistantMessageContent != null) {
                    try {
                        directorResponse = json.decodeFromString<DirectorResponse>(lastAssistantMessageContent!!)
                    } catch (e: kotlinx.serialization.SerializationException) {
                        scope.send(LLMMessage.SystemMessage("Director response was not valid JSON: ${e.message}. Content: '$lastAssistantMessageContent'"))
                        directorFailed = true
                    } catch (e: Exception) { // Catch other potential exceptions during parsing
                        scope.send(LLMMessage.SystemMessage("Error parsing director response: ${e.message}"))
                        directorFailed = true
                    }
                } else {
                    scope.send(LLMMessage.SystemMessage("Director did not provide an assistant response."))
                    directorFailed = true
                }

            } catch (e: Exception) {
                scope.send(LLMMessage.SystemMessage("Error calling Director agent: ${e.message}"))
                directorFailed = true
            }

            if (directorFailed || directorResponse == null) {
                scope.send(LLMMessage.SystemMessage("Halting execution due to director failure."))
                break
            }

            scope.send(LLMMessage.SystemMessage("Director decision: ${directorResponse.overallReason ?: "(No reason provided)"}"))

            if (directorResponse.isComplete) {
                scope.send(LLMMessage.SystemMessage("Director indicates task is complete."))
                break
            }

            val nextStepInfo = directorResponse.nextStep
            if (nextStepInfo == null) {
                scope.send(LLMMessage.SystemMessage("Director indicates task is not complete, but provided no next step. Halting."))
                break
            }

            // 3. Execute the Step
            val agentToExecute = orchestrator.getAgent(nextStepInfo.agentId)
            if (agentToExecute == null) {
                scope.send(LLMMessage.SystemMessage("Error: Agent '${nextStepInfo.agentId}' for step $currentStep not found. Halting."))
                conversation.executedSteps.add(
                    ExecutedStep(
                        agentId = nextStepInfo.agentId,
                        action = nextStepInfo.action,
                        status = StepStatus.FAILED,
                        error = "Agent not found"
                    )
                )
                break
            }

            val executedStepRecord = ExecutedStep(
                agentId = agentToExecute.id,
                action = nextStepInfo.action,
                status = StepStatus.RUNNING
            )
            conversation.executedSteps.add(executedStepRecord)

            scope.send(LLMMessage.SystemMessage("Executing Step $currentStep: Agent '${agentToExecute.id}', Action: '${nextStepInfo.action}'"))

            try {
                // Prepare input for the agent, including original request and specific action
                val stepInputContent = buildString {
                    append("Original Request: ${conversation.originalUserRequest}\n")
                    // Maybe include summary of previous steps if needed?
                    // append("Previous Steps Summary: ...\n")
                    append("Your Current Task: ${nextStepInfo.action}")
                }
                val stepMessage = triggerMessage.copy(
                    content = stepInputContent,
                    recipient = agentToExecute.id
                )

                // Execute and collect results, sending them through the main flow
                agentToExecute.process(stepMessage, conversation)
                    .catch { e ->
                        executedStepRecord.status = StepStatus.FAILED
                        executedStepRecord.error = "Agent ${agentToExecute.id} failed: ${e.message}"
                        scope.send(LLMMessage.SystemMessage("Workflow Error (Step $currentStep): ${executedStepRecord.error}"))
                        throw e // Re-throw to break the loop
                    }
                    .collect { resultMessage ->
                        scope.send(resultMessage) // Emit agent's messages
                        // Optionally summarize result for the record
                        // executedStepRecord.resultSummary = resultMessage.content // (Needs refinement)
                    }

                executedStepRecord.status = StepStatus.COMPLETED
                scope.send(LLMMessage.SystemMessage("Step $currentStep completed successfully."))
                currentStep++ // Move to the next potential step

            } catch (e: Exception) {
                // Error already recorded and sent by the catch block above.
                // Break the loop on agent execution failure.
                break
            }
        } // End while loop

        if (currentStep > maxSteps) {
            scope.send(LLMMessage.SystemMessage("Reached maximum step limit ($maxSteps). Stopping execution."))
            // Mark last step as potentially failed or incomplete?
        }
    }


    fun getHistory(conversationId: String): List<LLMMessage>? {
        return conversations[conversationId]?.messages?.toList()
    }

    fun getHandoffs(conversationId: String): List<Handoff>? {
        return conversations[conversationId]?.handoffs?.toList()
    }
}