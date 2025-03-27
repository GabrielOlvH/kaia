package dev.gabrielolv.kaia.core

import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider
import dev.gabrielolv.kaia.utils.nextThreadId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class HandoffManager(
    val orchestrator: Orchestrator,
    private val provider: LLMProvider
) {
    private val conversations = ConcurrentHashMap<String, Conversation>()
    private val conversationLocks = ConcurrentHashMap<String, Mutex>()
    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

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
                    orchestrator.processWithAgent(plannerAgentId, message)
                        .catch { e ->
                            val errorMsg = LLMMessage.SystemMessage(content = "Workflow failed: ${e.message}")
                            send(errorMsg)
                            conversation.messages.add(errorMsg)
                        }
                        .onCompletion { cause ->
                            if (cause == null && conversation.currentWorkflow?.steps?.lastOrNull()?.status == StepStatus.COMPLETED) {
                               // managerScope.launch {
                                    performFinalVerification(conversationId, this@channelFlow)
                              //  }
                            } else if (cause != null) {
                                val errorMsg =
                                    LLMMessage.SystemMessage(content = "Workflow interrupted: ${cause.message}")
                                send(errorMsg)
                                conversation.messages.add(errorMsg)
                            }
                        }
                        .collect { llmMessage ->
                            send(llmMessage)
                            conversation.messages.add(llmMessage)
                        }
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
            return@channelFlow
        }

        conversation.currentWorkflow = workflow
        conversation.currentStepIndex = 0
        var previousStepResult: List<LLMMessage>? = null
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
                    if (stepIndex > 0 && workflow.steps[stepIndex - 1].resultSummary != null) {
                        append("Previous Step (${workflow.steps[stepIndex - 1].agentId}) Result Summary: ${workflow.steps[stepIndex - 1].resultSummary}\n")
                    }
                    append("Your Task: ${step.action}")
                }
                val stepMessage = initialMessage.copy(content = stepInputContent, recipient = agent.id)

                val stepResults = mutableListOf<LLMMessage>()
                agent.process(stepMessage)
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
                step.resultSummary =
                    stepResults.filterIsInstance<LLMMessage.AssistantMessage>().lastOrNull()?.content?.take(100)
                        ?: "Completed"
                previousStepResult = stepResults
                conversation.currentStepIndex++
            } catch (e: Exception) {
                for (i in (stepIndex + 1) until workflow.steps.size) {
                    workflow.steps[i].status = StepStatus.SKIPPED
                }
                break
            }
        }

        if (conversation.currentStepIndex == workflow.steps.size && workflow.steps.last().status == StepStatus.COMPLETED) {
            send(LLMMessage.SystemMessage(content = "Workflow completed successfully."))
        } else {
            send(LLMMessage.SystemMessage(content = "Workflow finished with errors or was interrupted."))
        }
    }

    /**
     * Performs the final verification step after successful workflow completion.
     */
    private suspend fun performFinalVerification(
        conversationId: String,
        flowCollector: kotlinx.coroutines.channels.SendChannel<LLMMessage>
    ) {
        val conversation = conversations[conversationId] ?: return
        val lastWorkflow = conversation.currentWorkflow

        val verificationPrompt = """
        The user's request has been processed through the following steps:
        ${lastWorkflow?.steps?.joinToString("\n") { "- Agent ${it.agentId}: ${it.action} (${it.status})" }}

        Based on the original request and the steps taken, please confirm with the user if their request has been fully addressed or if anything else is needed. Be concise and helpful.
        """

        val verificationOptions = LLMOptions(systemPrompt = verificationPrompt, temperature = 0.5)

        try {
            flowCollector.send(LLMMessage.SystemMessage(content = "Performing final verification..."))
            provider.generate("Please verify the outcome.", verificationOptions)
                .collect { verificationMessage ->
                    flowCollector.send(verificationMessage)
                    conversation.messages.add(verificationMessage)
                }
        } catch (e: Exception) {
            val errorMsg = LLMMessage.SystemMessage(content = "Error during final verification: ${e.message}")
            flowCollector.send(errorMsg)
            conversation.messages.add(errorMsg)
        }
    }

    fun getHistory(conversationId: String): List<LLMMessage>? {
        return conversations[conversationId]?.messages?.toList()
    }

    fun getHandoffs(conversationId: String): List<Handoff>? {
        return conversations[conversationId]?.handoffs?.toList()
    }
}