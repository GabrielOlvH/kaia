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
    private val requestTransformer: (String, LLMOptions) -> Any,
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

    override fun generate(prompt: String, options: LLMOptions): Flow<LLMMessage> = flow {
        // Emit system message if provided
        options.systemPrompt?.let {
            emit(LLMMessage.SystemMessage(it))
        }

        // Emit user message
        emit(LLMMessage.UserMessage(prompt))

        val requestBody = requestTransformer(prompt, options)

        val response: JsonElement = client.post(url) {
            contentType(ContentType.Application.Json)
            this@CustomProvider.headers.forEach { (key, value) ->
                header(key, value)
            }
            setBody(requestBody)
        }.body()

        val result = responseTransformer(response)

        // Emit assistant message
        emit(LLMMessage.AssistantMessage(result.content, result.rawResponse))
    }
}