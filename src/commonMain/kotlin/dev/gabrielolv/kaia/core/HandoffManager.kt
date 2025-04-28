package dev.gabrielolv.kaia.core

import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.utils.nextThreadId
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class HandoffManager(
    val orchestrator: Orchestrator,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {
    private val conversations = mutableMapOf<String, Conversation>()
    private val lock = Mutex()
    private val maxSteps = 10
    /**
     * Start a new conversation. The initial agent is now the planner.
     */
    suspend fun startConversation(
        conversationId: String = nextThreadId,
        initialMessageContent: String
    ): String {
        val conversation = Conversation(
            id = conversationId,
            originalUserRequest = initialMessageContent
        )
        lock.withLock { conversations[conversationId] = conversation }
        return conversationId
    }

    suspend fun getConversation(conversationId: String): Conversation? {
        return lock.withLock { conversations[conversationId] }
    }

    /**
     * Send a message to the conversation. This will trigger the planner agent first.
     */
    suspend fun sendMessage(
        conversationId: String,
        message: LLMMessage.UserMessage,
        directorAgentId: String, // ID of the agent created by withDirectorAgent
    ): Flow<LLMMessage>? {
        val conversation = lock.withLock { conversations[conversationId] }
            ?: return null // Or throw exception

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
                    val lastExecuted = conversation.executedSteps.lastOrNull()
                    if (lastExecuted?.status == StepStatus.COMPLETED && conversation.executedSteps.size > 0) {
                        send(LLMMessage.SystemMessage("Processing complete."))
                    } else if (conversation.executedSteps.any { it.status == StepStatus.FAILED }) {
                        send(LLMMessage.SystemMessage("Processing finished with errors."))
                    } else if (conversation.executedSteps.size >= maxSteps) {
                        send(LLMMessage.SystemMessage("Processing stopped: Maximum step limit reached."))
                    }
                }
            }
        }
    }


    private suspend fun manageStepByStepExecution(
        conversationId: String,
        directorAgentId: String,
        triggerMessage: LLMMessage.UserMessage, // The message that initiated this cycle
        scope: ProducerScope<LLMMessage>
    ) {
        val conversation = lock.withLock { conversations[conversationId] } ?: return

        suspend fun emitAndStore(message: LLMMessage) {
            scope.send(message)
            conversation.append(message)
        }

        val directorAgent = orchestrator.getAgent(directorAgentId) ?: run {
            emitAndStore(LLMMessage.SystemMessage("Error: Director agent '$directorAgentId' not found."))
            return
        }

        var currentStep = 1
        while (currentStep <= maxSteps) {
            emitAndStore(LLMMessage.SystemMessage("Director: Deciding next step ($currentStep/$maxSteps)..."))

            // 1. Call Director Agent
            var directorResponse: DirectorResponse? = null
            var directorFailed = false
            var lastAssistantMessageContent: String? = null
            try {
                val directorTrigger = triggerMessage.copy(
                    content = "Based on the history and original request, determine the next step or completion.",
                )
                conversation.messages.add(LLMMessage.UserMessage(directorTrigger.content))

                directorAgent.process(directorTrigger, conversation)
                    .mapNotNull { msg ->
                        // Look for the specific assistant message marked as director response
                        if (msg is LLMMessage.AssistantMessage) {
                            lastAssistantMessageContent = msg.content
                            conversation.append(msg) // Still store director responses in history
                        } else {
                            scope.send(msg)
                            conversation.append(msg) // Store system messages from director in history
                            null
                        }
                    }
                    .lastOrNull() // Expecting one final JSON response

                if (lastAssistantMessageContent != null) {
                    try {
                        directorResponse = json.decodeFromString<DirectorResponse>(lastAssistantMessageContent!!)
                    } catch (e: kotlinx.serialization.SerializationException) {
                        emitAndStore(LLMMessage.SystemMessage("Director response was not valid JSON: ${e.message}. Content: '$lastAssistantMessageContent'"))
                        directorFailed = true
                    } catch (e: Exception) { // Catch other potential exceptions during parsing
                        emitAndStore(LLMMessage.SystemMessage("Error parsing director response: ${e.message}"))
                        directorFailed = true
                    }
                } else {
                    scope.send(LLMMessage.SystemMessage("Director did not provide an assistant response."))
                    directorFailed = true
                }

            } catch (e: Exception) {
                emitAndStore(LLMMessage.SystemMessage("Error calling Director agent: ${e.message}"))
                directorFailed = true
            }

            if (directorFailed || directorResponse == null) {
                emitAndStore(LLMMessage.SystemMessage("Halting execution due to director failure."))
                break
            }

            emitAndStore(LLMMessage.SystemMessage("Director decision: ${directorResponse.overallReason ?: "(No reason provided)"}"))

            if (directorResponse.isComplete) {
                emitAndStore(LLMMessage.SystemMessage("Director indicates task is complete."))
                break
            }

            val nextStepInfo = directorResponse.nextStep
            if (nextStepInfo == null) {
                emitAndStore(LLMMessage.SystemMessage("Director indicates task is not complete, but provided no next step. Halting."))
                break
            }

            // 3. Execute the Step
            val agentToExecute = orchestrator.getAgent(nextStepInfo.agentId)
            if (agentToExecute == null) {
                val errorMsg = LLMMessage.SystemMessage("Error: Agent '${nextStepInfo.agentId}' for step $currentStep not found. Halting.")
                scope.send(errorMsg)
                conversation.append(errorMsg)
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

            val stepStartMsg = LLMMessage.SystemMessage("Executing Step $currentStep: Agent '${agentToExecute.id}', Action: '${nextStepInfo.action}'")
            scope.send(stepStartMsg)
            conversation.append(stepStartMsg)

            try {
                val stepInputContent = buildString {
                    append("Original Request: ${conversation.originalUserRequest}\n")
                    append("Your Current Task: ${nextStepInfo.action}")
                }
                val stepMessage = triggerMessage.copy(
                    content = stepInputContent
                )

                // Add the user message to the step's messages
                executedStepRecord.messages.add(LLMMessage.UserMessage(stepInputContent))

                agentToExecute.process(stepMessage, conversation)
                    .catch { e ->
                        executedStepRecord.status = StepStatus.FAILED
                        executedStepRecord.error = "Agent ${agentToExecute.id} failed: ${e.message}"
                        val errorMsg = LLMMessage.SystemMessage("Workflow Error (Step $currentStep): ${executedStepRecord.error}")
                        scope.send(errorMsg)
                        executedStepRecord.messages.add(errorMsg)
                        throw e
                    }
                    .collect { resultMessage ->
                        if (resultMessage is LLMMessage.SystemMessage)
                            executedStepRecord.messages.add(resultMessage)
                        emitAndStore(resultMessage)
                    }

                executedStepRecord.status = StepStatus.COMPLETED
                val completionMsg = LLMMessage.SystemMessage("Step $currentStep completed successfully.")
                scope.send(completionMsg)
                conversation.append(completionMsg)
                currentStep++ // Move to the next potential step

                if (directorResponse.waitForUserInput) {
                    val pauseMsg = LLMMessage.SystemMessage("Pausing execution. Waiting for user input.")
                    scope.send(pauseMsg)
                    conversation.append(pauseMsg)
                    break
                }
            } catch (e: Exception) {
                break
            }
        } // End while loop

        if (currentStep > maxSteps) {
            emitAndStore(LLMMessage.SystemMessage("Reached maximum step limit ($maxSteps). Stopping execution."))
        }
    }


    suspend fun getHistory(conversationId: String): List<LLMMessage>? {
        return lock.withLock { conversations[conversationId]?.messages?.toList() }
    }

    suspend fun getHandoffs(conversationId: String): List<Handoff>? {
        return lock.withLock { conversations[conversationId]?.handoffs?.toList() }
    }
}
