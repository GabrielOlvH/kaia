package dev.gabrielolv.kaia.core


/**
 * The core Agent interface that defines the basic functionality of an agent.
 */
interface Agent {
    val id: String
    val name: String
    val description: String

    /**
     * Process a message and generate a response
     */
    suspend fun process(message: Message): Message

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

class AgentBuilder {
    var id: String = ""
    var name: String = ""
    var description: String = ""
    var processor: suspend (Message) -> Message = { message ->
        Message(content = "Default response to: ${message.content}")
    }

    fun build(): Agent = BaseAgent(
        id = id.takeIf { it.isNotEmpty() } ?: "agent-${System.currentTimeMillis()}",
        name = name,
        description = description,
        processor = processor
    )
}

private class BaseAgent(
    override val id: String,
    override val name: String,
    override val description: String,
    private val processor: suspend (Message) -> Message
) : Agent {
    override suspend fun process(message: Message): Message {
        return processor(message)
    }
}