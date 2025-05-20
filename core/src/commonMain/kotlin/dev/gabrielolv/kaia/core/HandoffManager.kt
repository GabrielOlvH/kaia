package dev.gabrielolv.kaia.core

import dev.gabrielolv.kaia.core.model.*
import dev.gabrielolv.kaia.core.tenant.TenantContext
import dev.gabrielolv.kaia.core.tenant.withTenantContext
import dev.gabrielolv.kaia.core.tools.ToolManager
import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.utils.nextThreadId
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AgentTaskContext(
    val originalUserRequest: String,
    val currentTask: String,
    val reasonForTask: String?
)

class HandoffManager(
    val orchestrator: Orchestrator,
    private val toolManager: ToolManager,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {
    private val conversations = mutableMapOf<String, Conversation>()
    private val lock = Mutex()
    private val maxSteps = 10
    /**
     * Start a new conversation. The initial agent is now the planner.
     */
    suspend fun startConversation(
        conversationId: String = nextThreadId
    ): String {
        val conversation = Conversation(id = conversationId)
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
        tenantContext: TenantContext,
    ): Flow<LLMMessage>? {
        val conversation = lock.withLock { conversations[conversationId] }
            ?: return null // Or throw exception

        return channelFlow {
            withTenantContext(tenantContext) {
                val userMessage = LLMMessage.UserMessage(content = message.content)
                conversation.append(userMessage)


                try {
                    manageStepByStepExecution(
                        conversation,
                         message,
                        directorAgentId,
                        message,
                        this@channelFlow
                    )
                } catch (e: Exception) {
                    val errorMsg = LLMMessage.SystemMessage(
                        content = "Step-by-step execution failed: ${e.message}"
                    )
                    send(errorMsg)
                    conversation.append(errorMsg)
                } finally {
                    val lastExecuted = conversation.executedSteps.lastOrNull()
                    if (lastExecuted?.status == StepStatus.COMPLETED && conversation.executedSteps.isNotEmpty()) {
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
        conversation: Conversation,
        message: LLMMessage.UserMessage,
        directorAgentId: String,
        triggerMessage: LLMMessage.UserMessage,
        scope: ProducerScope<LLMMessage>
    ) {

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

            var directorOutput: DirectorOutput? = null
            var directorFailed = false

            try {
                val directorTrigger = triggerMessage.copy(
                    content = "Based on the history and original request, determine the next step or completion.",
                )
                directorAgent.process(directorTrigger, conversation)
                    .collect { result ->
                        when (result) {
                            is StructuredResult<*> -> {
                                if (result.data is DirectorOutput) {
                                    directorOutput = result.data
                                    result.rawMessage?.let { emitAndStore(it) }
                                        ?: result.rawContent?.let { emitAndStore(LLMMessage.SystemMessage(it)) }
                                } else {
                                    emitAndStore(LLMMessage.SystemMessage("Director returned unexpected structured data type: ${result.data::class.simpleName}"))
                                    result.rawMessage?.let { conversation.append(it) }
                                        ?: result.rawContent?.let { conversation.append(LLMMessage.SystemMessage(it)) }
                                    directorFailed = true
                                }
                            }
                            is ErrorResult -> {
                                emitAndStore(LLMMessage.SystemMessage("Director Error: ${result.message}"))
                                result.rawMessage?.let { conversation.append(it) }
                                directorFailed = true
                            }
                            is SystemResult -> {
                                emitAndStore(LLMMessage.SystemMessage("Director System Message: ${result.message}"))
                                result.rawMessage?.let { conversation.append(it) }
                            }
                            is TextResult -> {
                                emitAndStore(LLMMessage.AssistantMessage(result.content))
                                result.rawMessage?.let { conversation.append(it) }
                            }
                            is ToolCallResult, is ToolResponseResult -> {
                                emitAndStore(LLMMessage.SystemMessage("Director unexpectedly returned Tool result. Ignoring."))
                                result.rawMessage?.let { conversation.append(it) }
                            }
                        }
                    }

                if (directorOutput == null && !directorFailed) {
                    emitAndStore(LLMMessage.SystemMessage("Director did not provide a structured DirectorOutput response."))
                    directorFailed = true
                }

            } catch (e: Exception) {
                emitAndStore(LLMMessage.SystemMessage("Error calling/collecting Director agent results: ${e.message}"))
                directorFailed = true
            }

            if (directorFailed || directorOutput == null) {
                emitAndStore(LLMMessage.SystemMessage("Halting execution due to director failure or missing output."))
                break
            }

            emitAndStore(LLMMessage.SystemMessage("Director decision: ${directorOutput.reasoningTrace}"))



            val nextStepInfo = directorOutput.nextStep
            if (nextStepInfo == null) {
                emitAndStore(LLMMessage.SystemMessage("Director indicates task is not complete, but provided no next step. Halting."))
                break
            }

            val agentToExecute = orchestrator.getAgent(nextStepInfo.agentId)
            if (agentToExecute == null) {
                val errorMsg = LLMMessage.SystemMessage("Error: Agent '${nextStepInfo.agentId}' for step $currentStep not found. Halting.")
                emitAndStore(errorMsg) // Use helper
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

            val stepStartMsg = LLMMessage.SystemMessage("Executing Step $currentStep: Agent '${agentToExecute.id}', Action: '${nextStepInfo.action}', Reason: '${nextStepInfo.reason}'")
            emitAndStore(stepStartMsg)
            executedStepRecord.messages.add(stepStartMsg) // Also log to step record

            var stepFailed = false
            var agentOutputReceived = false
            var toolCallMade = false

            try {
                // Prepare context for the agent (similar to before)
                val agentContext = AgentTaskContext(
                    originalUserRequest = message.content,
                    currentTask = nextStepInfo.action,
                    reasonForTask = nextStepInfo.reason
                )
                val stepInputContent = json.encodeToString(AgentTaskContext.serializer(), agentContext)
                val stepMessage = LLMMessage.UserMessage(stepInputContent)
                executedStepRecord.messages.add(stepMessage) // Log input to step

                // Process the agent's results
                agentToExecute.process(stepMessage, conversation)
                    .collect { agentResult ->
                        agentResult.rawMessage?.let { executedStepRecord.messages.add(it) }

                        when (agentResult) {
                            is TextResult -> {
                                val msg = LLMMessage.AssistantMessage(agentResult.content)
                                emitAndStore(msg) // Emit to client and main history
                                agentOutputReceived = true
                            }
                            is StructuredResult<*> -> {
                                // Emit the raw content/message, and maybe a structured representation?
                                val msg = agentResult.rawMessage ?: LLMMessage.AssistantMessage(agentResult.rawContent ?: "[Structured Data Received]")
                                emitAndStore(msg)
                                // Log the structured data itself to the step record for debugging
                                executedStepRecord.messages.add(LLMMessage.SystemMessage("Step Data: ${agentResult.data.toString().take(100)}...")) // Truncate for log
                                agentOutputReceived = true
                            }
                            is SystemResult -> {
                                val msg = LLMMessage.SystemMessage(agentResult.message)
                                emitAndStore(msg)
                            }
                            is ErrorResult -> {
                                val errorMsgContent = "Agent Error (Step $currentStep, Agent ${agentToExecute.id}): ${agentResult.message}"
                                val msg = LLMMessage.SystemMessage(errorMsgContent)
                                emitAndStore(msg)
                                emitAndStore(LLMMessage.AssistantMessage("There was an issue with your request. Please try again"))
                                executedStepRecord.error = agentResult.message
                                stepFailed = true
                                // Don't break collect, let agent finish if it can, but mark step as failed
                            }
                            is ToolCallResult -> {
                                toolCallMade = true
//                                agentResult.toolCalls.forEach { toolCall ->
//                                    val toolMsg =
//                                        LLMMessage.SystemMessage("Step $currentStep: Agent requests tool call: ${toolCall.name} with args: ${toolCall.arguments}")
//                                    emitAndStore(toolMsg) // Inform client
//                                    executedStepRecord.messages.add(toolMsg) // Log to step
//
//                                    try {
//                                        toolManager.executeToolFromJson(toolCall.id, toolCall.name, toolCall.arguments)
//                                            .onRight { toolResult ->
//                                                val resultMsgContent = "Tool Result (${toolCall.name}): ${toolResult.result}"
//                                                val toolResponseMsg = LLMMessage.SystemMessage(resultMsgContent)
//                                                executedStepRecord.messages.add(toolResponseMsg) // Log result
//                                                val toolOutputMsg = LLMMessage.ToolResponseMessage(toolCall.id, toolResult.result)
//                                                emitAndStore(toolOutputMsg)
//                                            }.onLeft { error ->
//                                                val toolErrorMsg =
//                                                    LLMMessage.SystemMessage("Tool execution failed for ${toolCall.name}: $error")
//                                                emitAndStore(toolErrorMsg)
//                                                executedStepRecord.messages.add(toolErrorMsg)
//                                                executedStepRecord.error = "Tool exception: $error"
//
//                                                executedStepRecord.error = "Tool failed: $error"
//                                                conversation.append(
//                                                    LLMMessage.ToolResponseMessage(
//                                                        toolCall.id,
//                                                        "Execution Error: $error"
//                                                    )
//                                                )
//                                                stepFailed = true // Mark step as failed if tool fails
//                                            }
//
//
//                                    } catch (e: Exception) {
//                                        val toolErrorMsg =
//                                            LLMMessage.SystemMessage("Unexpected error during tool execution for ${toolCall.name}: ${e.message}")
//                                        emitAndStore(toolErrorMsg)
//                                        executedStepRecord.messages.add(toolErrorMsg)
//                                        executedStepRecord.error = "Unexpected tool error: ${e.message}"
//                                        conversation.append(
//                                            LLMMessage.ToolResponseMessage(
//                                                toolCall.id,
//                                                "Unexpected Error: ${e.message}"
//                                            )
//                                        )
//                                        stepFailed = true
//                                    }
//                                }
                            }
                            is ToolResponseResult -> {
                                agentResult.toolResults.forEach { toolResult ->
                                    val msg = LLMMessage.SystemMessage("Step $currentStep: Agent provided tool response for call ${toolResult.toolCallId}: ${toolResult.result}")
                                    emitAndStore(msg)
                                    executedStepRecord.messages.add(msg)
                                }
                            }
                        }
                    }

                if (stepFailed) {
                    executedStepRecord.status = StepStatus.FAILED
                    emitAndStore(LLMMessage.SystemMessage("Step $currentStep failed."))
                    emitAndStore(LLMMessage.SystemMessage("Halting execution due to failure in step $currentStep."))
                    break
                } else {
                    executedStepRecord.status = StepStatus.COMPLETED
                    val completionMsg = LLMMessage.SystemMessage("Step $currentStep completed successfully by Agent ${agentToExecute.id}.")
                    emitAndStore(completionMsg)
                    executedStepRecord.messages.add(completionMsg)

                    // Check if agent produced any direct output or made a tool call
                    if (!agentOutputReceived && !toolCallMade) {
                        val noOutputMsg = LLMMessage.SystemMessage("Agent ${agentToExecute.id} completed step $currentStep without generating specific output or tool calls.")
                        executedStepRecord.messages.add(noOutputMsg)
                        emitAndStore(noOutputMsg)
                    }


                    currentStep++ // Move to the next potential step ONLY if this step succeeded
                }

            } catch (e: Exception) {
                // Catch exceptions from agent.process() or during collect { }
                val errorMsgContent = "Unhandled exception during step $currentStep (Agent ${agentToExecute.id}): ${e.message}"
                val errorMsg = LLMMessage.SystemMessage(errorMsgContent)
                emitAndStore(errorMsg)

                executedStepRecord.status = StepStatus.FAILED
                executedStepRecord.error = "Unhandled exception: ${e.message}"
                executedStepRecord.messages.add(errorMsg) // Add to step log

                emitAndStore(LLMMessage.SystemMessage("Halting execution due to unhandled exception in step $currentStep."))
                break // Exit the loop on failure
            }
            if (directorOutput.waitForUserInput) {
                emitAndStore(LLMMessage.SystemMessage("Director indicates task requires user input."))
                break
            }
            if (directorOutput.isComplete) {
                emitAndStore(LLMMessage.SystemMessage("Director indicates task is complete."))
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

    /**
     * Loads conversation history into an existing conversation.
     * If the conversation doesn't exist, it will be created.
     *
     * @param conversationId The ID of the conversation to load history into.
     * @param messages The list of messages to load as history.
     * @return True if the history was successfully loaded, false otherwise.
     */
    suspend fun loadConversationHistory(
        conversationId: String,
        messages: List<LLMMessage>
    ): Boolean {
        return lock.withLock {
            val conversation = conversations[conversationId] ?: Conversation(id = conversationId).also {
                conversations[conversationId] = it
            }
            
            // Clear existing messages if any
            conversation.messages.clear()
            
            // Add all provided messages
            conversation.messages.addAll(messages)
            
            true
        }
    }

    suspend fun getHandoffs(conversationId: String): List<Handoff>? {
        return lock.withLock { conversations[conversationId]?.handoffs?.toList() }
    }
}
