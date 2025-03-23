package dev.gabrielolv.kaia.core.agents

import dev.gabrielolv.kaia.core.HandoffManager
import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent that can automatically decide when to hand off to other agents
 * Updated to support Flow-based processing
 */
fun Agent.Companion.withHandoff(
    handoffManager: HandoffManager,
    conversationId: String,
    provider: LLMProvider,
    agentDatabase: Map<String, String> = handoffManager.orchestrator.getAgentDatabase(),
    systemPrompt: String? = null,
    block: AgentBuilder.() -> Unit
): Agent {
    val builder = AgentBuilder().apply(block)

    // Build agent catalog string
    val agentCatalog = agentDatabase.entries.joinToString("\n") {
        "${it.key}: ${it.value}"
    }

    // Create a handoff evaluation prompt
    val handoffEvalPrompt = """
    You are an agent coordinator. You need to decide whether the user's message should be:
    1. Handled by the current agent (${builder.id}: ${builder.description})
    2. Handed off to another specialized agent
    
    Available agents:
    $agentCatalog
    
    Analyze the user's message and determine if another agent would be better suited to handle it.
    If a handoff is needed, respond with a JSON object in this format:
    {
      "handoff": true,
      "targetAgentId": "[agent ID]",
      "reason": "[brief explanation]"
    }
    
    If no handoff is needed, respond with:
    {
      "handoff": false
    }
    
    Base your decision on the expertise required to address the user's query.
    """

    // Flow-based processor
    builder.processor = { message ->
        flow {
            // First, evaluate if we should hand off
            val evaluationOptions = LLMOptions(
                systemPrompt = handoffEvalPrompt,
                temperature = 0.1 // Low temperature for more deterministic decisions
            )

            // Collect the evaluation response
            val evaluationMessages = provider.generate(message.content, evaluationOptions).toList()
            val evaluationContent = evaluationMessages
                .filterIsInstance<LLMMessage.AssistantMessage>()
                .firstOrNull()?.content ?: ""

            try {
                // Parse the evaluation response
                val decision = Json.parseToJsonElement(evaluationContent).jsonObject
                val shouldHandoff = decision["handoff"]?.jsonPrimitive?.boolean ?: false

                if (shouldHandoff) {
                    val targetAgentId = decision["targetAgentId"]?.jsonPrimitive?.content ?: ""
                    val reason = decision["reason"]?.jsonPrimitive?.content ?: "No reason provided"

                    // Create handoff
                    val success = handoffManager.handoff(
                        conversationId = conversationId,
                        targetAgentId = targetAgentId,
                        reason = reason
                    )

                    if (success) {
                        // Process with new agent
                        val targetAgent = handoffManager.getConversation(conversationId)?.currentAgentId?.let {
                            handoffManager.orchestrator.getAgent(it)
                        }

                        if (targetAgent != null) {
                            // Forward the message flow from the target agent
                            targetAgent.process(message).collect { emit(it) }
                        } else {
                            emit(LLMMessage.SystemMessage(content = "Handoff to agent $targetAgentId successful, but could not process message."))
                        }
                    } else {
                        // Handoff failed, process normally
                        val options = LLMOptions(systemPrompt = systemPrompt)
                        val handoffFailedPrefix = "I tried to hand off your request to a more specialized agent, but couldn't. I'll do my best to help.\n\n"

                        // Collect messages from the provider
                        provider.generate(message.content, options).collect(::emit)
                    }
                } else {
                    // No handoff needed, process normally
                    val options = LLMOptions(systemPrompt = systemPrompt)

                    // Collect messages from the provider
                    provider.generate(message.content, options).collect(::emit)
                }
            } catch (e: Exception) {
                // Parsing failed, process normally
                val options = LLMOptions(systemPrompt = systemPrompt)

                // Collect messages from the provider
                provider.generate(message.content, options).collect(::emit)
            }
        }
    }

    return builder.build()
}