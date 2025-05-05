package dev.gabrielolv.kaia.examples.tools

import dev.gabrielolv.kaia.core.tools.ToolManager
import dev.gabrielolv.kaia.examples.getEnv
import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.providers.GeminiToolsProvider
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking

/**
 * Example demonstrating function calling with Google Gemini
 */
fun main() = runBlocking {
    // Create tool manager and register the weather tool
    val toolManager = ToolManager()
    toolManager.registerWeatherTool()
    
    // Get API key from environment variable
    val apiKey = getEnv("GEMINI_API_KEY") 
        ?: throw IllegalStateException("GEMINI_API_KEY environment variable not set")
    
    // Create Gemini provider with function calling capabilities
    val gemini = GeminiToolsProvider(
        apiKey = apiKey,
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        model = "gemini-2.5-pro-exp-03-25",
        toolManager = toolManager
    )
    
    // Create conversation with a user message asking about weather
    val messages = listOf(
        LLMMessage.SystemMessage(
            "You are a helpful assistant that provides accurate weather information."
        ),
        LLMMessage.UserMessage(
            "What's the weather like in San Francisco and New York? Please provide the temperature in both celsius and fahrenheit."
        )
    )
    
    // Set options
    val options = LLMOptions(
        temperature = 0.7,
        maxTokens = 1000
    )
    
    println("Sending request to Google Gemini with function calling...")
    println("Messages: ${messages.joinToString("\n") { "${it::class.simpleName}: ${it.asPromptString()}" }}")
    println("Tools available: ${toolManager.getAllTools().joinToString(", ") { it.name }}")
    println("\n--- Response ---\n")
    
    // Generate response and collect messages
    gemini.generate(messages, options)
        .onEach { message ->
            when (message) {
                is LLMMessage.AssistantMessage -> {
                    println("🤖 Assistant: ${message.content}")
                }
                is LLMMessage.ToolCallMessage -> {
                    println("🔧 Tool Call: ${message.name}(${message.arguments})")
                }
                is LLMMessage.ToolResponseMessage -> {
                    println("🔄 Tool Response: ${message.content}")
                }
                is LLMMessage.SystemMessage -> {
                    println("⚠️ System: ${message.content}")
                }
                else -> println("Other message: $message")
            }
        }
        .collect()
}
