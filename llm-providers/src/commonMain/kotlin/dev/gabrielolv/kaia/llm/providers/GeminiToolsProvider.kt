package dev.gabrielolv.kaia.llm.providers

import dev.gabrielolv.kaia.core.tools.ToolManager
import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider
import dev.gabrielolv.kaia.utils.httpClient
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Gemini provider with function calling capabilities that supports chained tool calls
 * using Flow to emit all messages in the conversation
 */
class GeminiToolsProvider(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
    private val toolManager: ToolManager
) : LLMProvider {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    @Serializable
    private data class GeminiContent(
        val role: String? = null,
        val parts: List<GeminiPart>
    )

    @Serializable
    private data class GeminiPart(
        val text: String? = null,
        val functionCall: GeminiFunctionCall? = null,
        val functionResponse: GeminiFunctionResponse? = null
    )

    @Serializable
    private data class GeminiFunctionCall(
        val name: String,
        val args: String
    )

    @Serializable
    private data class GeminiFunctionResponse(
        val name: String,
        val response: JsonObject
    )

    @Serializable
    private data class GeminiRequest(
        val contents: List<GeminiContent>,
        val generationConfig: GeminiGenerationConfig? = null,
        val safetySettings: List<GeminiSafetySetting>? = null,
        val systemInstruction: GeminiContent? = null,
        val tools: List<GeminiTool>? = null
    )

    @Serializable
    private data class GeminiGenerationConfig(
        val temperature: Double? = null,
        val maxOutputTokens: Int? = null,
        val topP: Double? = null,
        val topK: Int? = null,
        val stopSequences: List<String>? = null
    )

    @Serializable
    private data class GeminiSafetySetting(
        val category: String,
        val threshold: String
    )

    @Serializable
    private data class GeminiTool(
        val functionDeclarations: List<GeminiFunctionDeclaration>
    )

    @Serializable
    private data class GeminiFunctionDeclaration(
        val name: String,
        val description: String,
        val parameters: JsonObject
    )

    @Serializable
    private data class GeminiResponse(
        val candidates: List<GeminiCandidate>,
        val promptFeedback: GeminiPromptFeedback? = null
    )

    @Serializable
    private data class GeminiCandidate(
        val content: GeminiContent,
        val finishReason: String? = null,
        val safetyRatings: List<GeminiSafetyRating>? = null
    )

    @Serializable
    private data class GeminiPromptFeedback(
        val safetyRatings: List<GeminiSafetyRating>? = null
    )

    @Serializable
    private data class GeminiSafetyRating(
        val category: String,
        val probability: String
    )

    private fun LLMMessage.toGeminiContent(): GeminiContent? = when (this) {
        is LLMMessage.UserMessage -> GeminiContent(
            role = "user",
            parts = listOf(GeminiPart(text = content))
        )
        is LLMMessage.AssistantMessage -> GeminiContent(
            role = "model",
            parts = listOf(GeminiPart(text = content))
        )
        is LLMMessage.SystemMessage -> GeminiContent(
            role = "system",
            parts = listOf(GeminiPart(text = content))
        )
        is LLMMessage.ToolCallMessage -> GeminiContent(
            role = "model",
            parts = listOf(
                GeminiPart(
                    functionCall = GeminiFunctionCall(
                        name = name,
                        args = json.encodeToString(arguments)
                    )
                )
            )
        )
        is LLMMessage.ToolResponseMessage -> GeminiContent(
            role = "user",
            parts = listOf(
                GeminiPart(
                    functionResponse = GeminiFunctionResponse(
                        name = toolCallId.substringAfterLast("-"),
                        response = JsonObject(mapOf("result" to JsonPrimitive(content)))
                    )
                )
            )
        )
    }

    /**
     * Helper function to make API requests to avoid code duplication
     */
    private suspend fun makeGenerateContentRequest(request: GeminiRequest): GeminiResponse {
        return httpClient.post("$baseUrl/models/$model:generateContent?key=$apiKey") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }.body()
    }

    /**
     * Process function calls in parallel and return the response messages
     */
    @OptIn(ExperimentalTime::class)
    private suspend fun processFunctionCalls(
        content: GeminiContent,
        emitMessage: suspend (LLMMessage) -> Unit
    ) = coroutineScope {
        // Extract all function calls from parts
        val functionCalls = content.parts
            .mapNotNull { it.functionCall }
            .map { functionCall ->
                async {
                    val toolName = functionCall.name
                    val arguments = functionCall.args
                    val toolCallId = "${toolName}-${Clock.System.now().toEpochMilliseconds()}"

                    // Emit tool call message
                    emitMessage(
                        LLMMessage.ToolCallMessage(
                            toolCallId = toolCallId,
                            name = toolName,
                            arguments = json.decodeFromString(arguments)
                        )
                    )

                    val result = toolManager.executeTool(toolCallId, toolName, json.decodeFromString(arguments))

                    // Emit tool response message
                    val toolResponseMessage = LLMMessage.ToolResponseMessage(
                        toolCallId = toolCallId,
                        content = result.result
                    )
                    emitMessage(toolResponseMessage)

                    // Return the content for the API
                    GeminiContent(
                        role = "user",
                        parts = listOf(
                            GeminiPart(
                                functionResponse = GeminiFunctionResponse(
                                    name = toolName,
                                    response = JsonObject(mapOf("result" to JsonPrimitive(result.result)))
                                )
                            )
                        )
                    )
                }
            }

        // Wait for all function calls to complete and return the results
        functionCalls.map { it.await() }
    }

    override fun generate(
        messages: List<LLMMessage>,
        options: LLMOptions
    ): Flow<LLMMessage> = channelFlow {
        // Extract system message
        val systemMessage = options.systemPrompt ?: 
            messages.filterIsInstance<LLMMessage.SystemMessage>().lastOrNull()?.content

        // Create system instruction if available
        val systemInstruction = systemMessage?.let {
            GeminiContent(
                role = "system",
                parts = listOf(GeminiPart(text = it))
            )
        }

        // Convert registered tools to Gemini format
        val tools = toolManager.getAllTools().takeIf { it.isNotEmpty() }?.let {
            listOf(
                GeminiTool(
                    functionDeclarations = it.map { tool ->
                        GeminiFunctionDeclaration(
                            name = tool.name,
                            description = tool.description,
                            parameters = tool.parameterSchema
                        )
                    }
                )
            )
        }

        // Prepare conversation messages (excluding system messages)
        val conversationMessages = messages.filterNot { it is LLMMessage.SystemMessage }
            .mapNotNull { it.toGeminiContent() }

        // Ensure there's at least one message
        if (conversationMessages.isEmpty()) {
            send(LLMMessage.SystemMessage("Error: No valid messages found to send to Gemini API after filtering."))
            return@channelFlow
        }

        // Create generation config
        val generationConfig = GeminiGenerationConfig(
            temperature = options.temperature,
            maxOutputTokens = options.maxTokens ?: 8192,
            stopSequences = options.stopSequences.takeIf { it.isNotEmpty() }
        )

        // --- Tool call loop ---
        var currentMessages = conversationMessages.toList()
        val maxIterations = 10
        var iteration = 0

        while (iteration < maxIterations) {
            // Create request
            val request = GeminiRequest(
                contents = currentMessages,
                generationConfig = generationConfig,
                systemInstruction = systemInstruction,
                tools = tools
            )

            val response: GeminiResponse = try {
                makeGenerateContentRequest(request)
            } catch (e: Exception) {
                send(LLMMessage.SystemMessage("Error calling Gemini API: ${e.message}"))
                return@channelFlow
            }

            // Extract content from response
            val candidate = response.candidates.firstOrNull()
            if (candidate == null) {
                send(LLMMessage.SystemMessage("Error: No response candidates returned from Gemini API."))
                return@channelFlow
            }

            val responseContent = candidate.content
            
            // Check if there are function calls in the response
            val hasFunctionCalls = responseContent.parts.any { it.functionCall != null }
            
            if (hasFunctionCalls) {
                // Add the model's message with function calls to our history
                currentMessages = currentMessages + responseContent
                
                // Process function calls and get responses
                val functionResponseContents = processFunctionCalls(responseContent) { message ->
                    send(message) // Emit tool call and response messages
                }
                
                // Add function responses to conversation history
                currentMessages = currentMessages + functionResponseContents
                iteration++
            } else {
                // No function calls, this is the final assistant message
                val textContent = responseContent.parts.firstOrNull { it.text != null }?.text
                if (textContent != null) {
                    val finalAssistantMessage = LLMMessage.AssistantMessage(
                        content = textContent,
                        rawResponse = Json.encodeToJsonElement(response)
                    )
                    send(finalAssistantMessage)
                } else {
                    send(LLMMessage.SystemMessage("Warning: Gemini response contained no text content."))
                }
                break // Exit loop
            }
        }

        if (iteration == maxIterations) {
            send(LLMMessage.SystemMessage("Error: Reached maximum function call iterations ($maxIterations)."))
        }
    }
}
