package dev.gabrielolv.kaia.core.agents

import dev.gabrielolv.kaia.core.Conversation
import dev.gabrielolv.kaia.core.model.AgentResult
import dev.gabrielolv.kaia.core.model.SystemResult
import dev.gabrielolv.kaia.llm.LLMMessage
import io.ktor.util.date.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The core Agent interface that defines the basic functionality of an agent.
 * Updated to support structured AgentResult processing.
 */
interface Agent {
    val id: String
    val name: String
    val description: String

    /**
     * Process a message and return a flow of agent results.
     * This allows for streaming responses, structured data, tool calls, and errors.
     */
    fun process(message: LLMMessage.UserMessage, conversation: Conversation): Flow<AgentResult>

    companion object {
        /**
         * Create a new agent with the given configuration
         */
        fun create(block: AgentBuilder.() -> Unit): Agent {
            val builder = AgentBuilder()
            builder.block()
            return builder.build()
        }
    }
}

open class AgentBuilder {
    var id: String = ""
    var name: String = ""
    var description: String = ""

    var processor: (LLMMessage.UserMessage, Conversation) -> Flow<AgentResult> = { message, conversation ->
        flow {
            emit(SystemResult(message = "Default response to: ${message.content} in conv ${conversation.id}"))
        }
    }

    fun build(): Agent = BaseAgent(
        id = id.takeIf { it.isNotEmpty() } ?: "agent-${getTimeMillis()}",
        name = name,
        description = description,
        processor = processor
    )
}

private class BaseAgent(
    override val id: String,
    override val name: String,
    override val description: String,
    private val processor: (LLMMessage.UserMessage, Conversation) -> Flow<AgentResult>
) : Agent {

    override fun process(message: LLMMessage.UserMessage, conversation: Conversation): Flow<AgentResult> {
        return processor(message, conversation)
    }
}