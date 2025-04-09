package dev.gabrielolv.kaia.llm.providers

import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider
import dev.gabrielolv.kaia.llm.LLMResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal class CustomProvider(
    private val url: String,
    private val headers: Map<String, String>,
    private val requestTransformer: (List<LLMMessage>, LLMOptions) -> Any,
    private val responseTransformer: (Any) -> LLMResponse
) : LLMProvider {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    override fun generate(
        messages: List<LLMMessage>, // Changed parameter
        options: LLMOptions
    ): Flow<LLMMessage> = flow {

        // --- History Trimming (Example - Adapt as needed for your custom API) ---
        val messagesToSend = mutableListOf<LLMMessage>()
        var systemMessage: LLMMessage.SystemMessage? = null

        // Extract system message (options override history)
        val systemPromptFromOptions = options.systemPrompt?.let { LLMMessage.SystemMessage(it) }
        val systemPromptFromHistory = messages.filterIsInstance<LLMMessage.SystemMessage>().lastOrNull()
        systemMessage = systemPromptFromOptions ?: systemPromptFromHistory
        systemMessage?.let { messagesToSend.add(it) }

        // Get conversation history (excluding system messages)
        val conversationMessages = messages.filter { it !is LLMMessage.SystemMessage }

        // Apply history size limit

        messagesToSend.addAll(conversationMessages)
        // --- End History Trimming Example ---


        // Use the (potentially trimmed) messages list with the transformer
        val requestBody = try {
            requestTransformer(messagesToSend, options)
        } catch (e: Exception) {
            emit(LLMMessage.SystemMessage("Error transforming request: ${e.message}"))
            return@flow
        }


        val response: JsonElement = try {
            client.post(url) {
                contentType(ContentType.Application.Json)
                this@CustomProvider.headers.forEach { (key, value) ->
                    header(key, value)
                }
                setBody(requestBody)
            }.body()
        } catch (e: Exception) {
            emit(LLMMessage.SystemMessage("Error calling custom API: ${e.message}"))
            return@flow
        }

        val result = try {
            responseTransformer(response)
        } catch (e: Exception) {
            emit(LLMMessage.SystemMessage("Error transforming response: ${e.message}"))
            return@flow
        }


        // Emit only the assistant message
        emit(LLMMessage.AssistantMessage(result.content, result.rawResponse))
    }
}