package dev.gabrielolv.kaia.examples

import dev.gabrielolv.kaia.core.KAIAgentSystem
import dev.gabrielolv.kaia.core.KAIAgentSystemBuilder
import dev.gabrielolv.kaia.core.agents.Agent
import dev.gabrielolv.kaia.core.agents.buildDirectorAgent
import dev.gabrielolv.kaia.core.agents.buildLLMAgent
import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMProvider
import kotlinx.coroutines.runBlocking


fun KAIAgentSystemBuilder.createGreetingAgent(provider: LLMProvider): Agent {
    return buildLLMAgent {
        id = "greeting-agent"
        name = "Greeting Agent"
        description = "Handles initial greetings and simple pleasantries."
        this.provider = provider
        systemPrompt = "You are a friendly assistant that greets users warmly."
        temperature = 0.5
    }
}

fun KAIAgentSystemBuilder.createInfoAgent(provider: LLMProvider): Agent {
    return buildLLMAgent {
        id = "info-agent"
        name = "Information Agent"
        description = "Provides information based on user queries."
        this.provider = provider
        systemPrompt = "You are a helpful AI assistant designed to provide factual information."
        temperature = 0.7
    }
}

fun KAIAgentSystemBuilder.createGoodbyeAgent(provider: LLMProvider): Agent {
    return buildLLMAgent {
        id = "goodbye-agent"
        name = "Goodbye Agent"
        description = "Handles farewell messages."
        this.provider = provider
        systemPrompt = "You are an assistant saying goodbye politely."
        temperature = 0.5
    }
}

fun KAIAgentSystemBuilder.createFallbackAgent(provider: LLMProvider): Agent {
    return buildLLMAgent {
        id = "fallback-agent"
        name = "Fallback Agent"
        description = "Handles requests that other agents cannot process."
        this.provider = provider
        systemPrompt = "You are a general-purpose assistant. Respond helpfully when no other agent can handle the request."
        temperature = 0.8
    }
}

// --- Main Test Execution ---

fun main() = runBlocking {
    val apiKey = getEnv("OPENAI_API_KEY")
    if (apiKey.isNullOrBlank()) {
        println("Error: OPENAI_API_KEY environment variable not set.")
        return@runBlocking
    }

    val openAIProvider: LLMProvider = try {
        LLMProvider.openAI(apiKey = apiKey, model = "gpt-4.1-mini")
    } catch (e: Exception) {
        println("Error initializing OpenAIProvider: ${e.message}")
        e.printStackTrace()
        return@runBlocking
    }

    // Build the system
    val agentSystem = KAIAgentSystem.build {
        val fallback = createFallbackAgent(openAIProvider)
        val greeting = createGreetingAgent(openAIProvider)
        val info = createInfoAgent(openAIProvider)
        val goodbye = createGoodbyeAgent(openAIProvider)

        val director = buildDirectorAgent {
            id = "director"
            name = "Main Director"
            description = "Routes requests to the appropriate agent (Greeting, Info, Goodbye) or uses Fallback."
            this.provider = openAIProvider
            agentDatabase = mapOf(
                greeting.id to greeting.description,
                info.id to info.description,
                goodbye.id to goodbye.description,
                fallback.id to fallback.description
            )
            this.fallbackAgent = fallback
            taskGoal = "Understand the user's intent (greeting, asking for info, saying goodbye) and route them to the correct agent. If unsure or the request is general, use the Fallback Agent."
        }

        // Designate the director agent
        designateDirector(director.id)
    }

    println("Agent System Initialized.")
    println("Enter your message (or type 'exit' to quit):")

    var conversationId: String? = null // Track the current conversation ID

        print("> ")
        val input = "hi!"
        if (input.equals("exit", ignoreCase = true)) {
            println("ye")

        }


        println("--- Processing ---")

        try {
            val runResult = if (conversationId == null) {
                // Start a new conversation with the first input
                agentSystem.run(initialInput = input)
            } else {
                // Continue existing conversation
                agentSystem.run(conversationId!!, input = input)
            }

            // Update conversation ID if it's the first run
            if (conversationId == null) {
                conversationId = runResult.conversationId
                println("[System] Started conversation: $conversationId")
            }

            // Collect and print messages from the flow
            runResult.messageFlow.collect { message ->
                when (message) {
                    is LLMMessage.UserMessage -> println("[User]: ${message.content}") // Should not happen often in flow, but good to handle
                    is LLMMessage.AssistantMessage -> println("[Assistant]: ${message.content}")
                    is LLMMessage.SystemMessage -> println("[System]: ${message.content}")
                    is LLMMessage.ToolCallMessage -> println("[ToolCall]: ${message.toolCallId}") // Basic display
                    is LLMMessage.ToolResponseMessage -> println("[ToolResponse]: ${message.content}") // Basic display
                    else -> println("[Unknown Message Type]: $message")
                }
            }
        } catch (e: Exception) {
            println("An unhandled error occurred during processing: ${e.message}")
            e.printStackTrace()
        }
        println("--- Ready for next input ---")


    println("Exiting.")
}

expect fun getEnv(name: String): String?
