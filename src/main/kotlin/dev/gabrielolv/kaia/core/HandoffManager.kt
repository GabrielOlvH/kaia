package dev.gabrielolv.kaia.core

import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.utils.nextThreadId
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class HandoffManager(
    val orchestrator: Orchestrator
) {
    private val conversations = ConcurrentHashMap<String, Conversation>()
    private val conversationLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Start a new conversation. The initial agent is now the planner.
     */
    fun startConversation(
        conversationId: String = nextThreadId,
    ): String {
        conversations[conversationId] = Conversation(
            id = conversationId,
            messages = mutableListOf()
        )
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
        plannerAgentId: String,
    ): Flow<LLMMessage>? {
        assert(message.content.isNotBlank()) { "Message cannot be blank" }
        assert(message.sender.isNotBlank()) { "Message sender cannot be empty" }

        val conversation = conversations[conversationId]
            ?: return null
        val lock = conversationLocks.computeIfAbsent(conversationId) { Mutex() }

        return channelFlow {
            lock.withLock {
                conversation.messages.add(LLMMessage.UserMessage(content = message.content))

                try {
                    orchestrator.processWithAgent(plannerAgentId, message, conversation)
                        .catch { e ->
                            val errorMsg = LLMMessage.SystemMessage(content = "Workflow failed: ${e.message}")
                            send(errorMsg)
                            conversation.messages.add(errorMsg)
                        }
                        .onCompletion { cause ->
                            if (cause == null && conversation.currentWorkflow?.steps?.lastOrNull()?.status == StepStatus.COMPLETED) {
                               send(LLMMessage.SystemMessage("Workflow completed successfully!"))
                            } else if (cause != null) {
                                val errorMsg =
                                    LLMMessage.SystemMessage(content = "Workflow interrupted: ${cause.message}")
                                send(errorMsg)
                                conversation.messages.add(errorMsg)
                            }
                        }
                        .onEach { llmMessage ->
                            send(llmMessage)
                            conversation.messages.add(llmMessage)
                        }
                        .collect()
                } catch (e: Exception) {
                    val errorMsg = LLMMessage.SystemMessage(content = "Failed to start planner: ${e.message}")
                    send(errorMsg)
                    conversation.messages.add(errorMsg)
                }
            }
        }
    }

    /**
     * Executes a planned workflow sequentially.
     * This is called by the planner agent.
     */
    internal fun executeWorkflow(
        conversationId: String,
        workflow: Workflow,
        initialMessage: Message
    ): Flow<LLMMessage> = channelFlow {
        val conversation = conversations[conversationId] ?: run {
            send(LLMMessage.SystemMessage(content = "Error: Conversation $conversationId not found for workflow execution."))
            close()
            return@channelFlow
        }

        conversation.currentWorkflow = workflow
        conversation.currentStepIndex = 0
        while (conversation.currentStepIndex < workflow.steps.size) {
            val stepIndex = conversation.currentStepIndex
            val step = workflow.steps[stepIndex]
            val agent = orchestrator.getAgent(step.agentId)

            if (agent == null) {
                step.status = StepStatus.FAILED
                step.error = "Agent ${step.agentId} not found."
                send(LLMMessage.SystemMessage(content = "Workflow Error (Step ${stepIndex + 1}): ${step.error}"))
                break
            }

            step.status = StepStatus.RUNNING
            send(LLMMessage.SystemMessage(content = "Executing Step ${stepIndex + 1}/${workflow.steps.size}: Agent '${agent.id}', Action: '${step.action}'"))

            try {
                val stepInputContent = buildString {
                    append("Original Request: ${initialMessage.content}\n")
                    append("Your Task: ${step.action}")
                }
                val stepMessage = initialMessage.copy(content = stepInputContent, recipient = agent.id)

                val stepResults = mutableListOf<LLMMessage>()
                agent.process(stepMessage, conversation)
                    .catch { e ->
                        step.status = StepStatus.FAILED
                        step.error = "Agent ${agent.id} failed: ${e.message}"
                        send(LLMMessage.SystemMessage(content = "Workflow Error (Step ${stepIndex + 1}): ${step.error}"))
                        throw e
                    }
                    .collect { resultMessage ->
                        send(resultMessage)
                        stepResults.add(resultMessage)
                    }

                step.status = StepStatus.COMPLETED
                conversation.currentStepIndex++
            } catch (e: Exception) {
                for (i in (stepIndex + 1) until workflow.steps.size) {
                    workflow.steps[i].status = StepStatus.SKIPPED
                }
                break
            }
        }

        if (conversation.currentStepIndex != workflow.steps.size || workflow.steps.last().status != StepStatus.COMPLETED) {
            send(LLMMessage.SystemMessage(content = "Workflow finished with errors or was interrupted."))
        }
    }

    fun getHistory(conversationId: String): List<LLMMessage>? {
        return conversations[conversationId]?.messages?.toList()
    }

    fun getHandoffs(conversationId: String): List<Handoff>? {
        return conversations[conversationId]?.handoffs?.toList()
    }
}