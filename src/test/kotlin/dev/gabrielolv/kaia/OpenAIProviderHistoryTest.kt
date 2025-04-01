package dev.gabrielolv.kaia

import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.providers.OpenAIProvider
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.shouldBe
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*

@OptIn(ExperimentalSerializationApi::class)
class OpenAIProviderHistoryTest : DescribeSpec({

    val testApiKey = "test-key"
    val testBaseUrl = "http://localhost/v1"
    val testModel = "gpt-test"

    // Reusable JSON configuration
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
        prettyPrint = false // Keep JSON compact for comparison
        namingStrategy = JsonNamingStrategy.SnakeCase // Match OpenAIProvider's internal client
    }

    // Helper to create a mock client that validates the request body
    fun createMockClient(
        expectedRequestBodyJson: String,
        dummyApiResponseJson: String = """
            {
                "id": "chatcmpl-123",
                "object": "chat.completion",
                "created": 1677652288,
                "model": "$testModel",
                "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "Mock response"},
                    "finish_reason": "stop"
                }],
                "usage": {"prompt_tokens": 9, "completion_tokens": 12, "total_tokens": 21}
            }
        """.trimIndent()
    ): HttpClient {
        val mockEngine = MockEngine { request ->
            request.method shouldBe HttpMethod.Post
            request.url.toString() shouldBe "$testBaseUrl/chat/completions"
            request.headers[HttpHeaders.Authorization] shouldBe "Bearer $testApiKey"

            // Validate the request body JSON
            val actualRequestBody = request.body.toByteArray().decodeToString()
            actualRequestBody shouldBeEqual expectedRequestBodyJson

            respond(
                content = dummyApiResponseJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        return HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json) // Use the same Json config
            }
        }
    }

    // --- Test Cases ---

    describe("OpenAIProvider history handling") {

        context("when history size is limited") {
            val history = listOf(
                LLMMessage.SystemMessage("Initial System Prompt."), // Should be ignored if options.systemPrompt is set
                LLMMessage.UserMessage("User message 1"),
                LLMMessage.AssistantMessage("Assistant response 1"),
                LLMMessage.UserMessage("User message 2"),
                LLMMessage.AssistantMessage("Assistant response 2"), // This one should be kept
                LLMMessage.ToolResponseMessage("tool_123", "Tool result"), // This one should be kept
                LLMMessage.UserMessage("User message 3") // This one should be kept (latest)
            )
            val options = LLMOptions(
                systemPrompt = "Override System Prompt.",
                historySize = 3 // Keep last 3 non-system messages + override system prompt
            )

            it("should send only the latest messages plus the override system prompt") {
                val expectedApiMessages = listOf(
                    mapOf("role" to "system", "content" to "Override System Prompt."),
                    mapOf("role" to "assistant", "content" to "Assistant response 2"),
                    // Expected tool message WITHOUT tool_call_id
                    mapOf("role" to "tool", "content" to "Tool result"),
                    mapOf("role" to "user", "content" to "User message 3")
                )
                val expectedRequestBody = buildJsonObject {
                    put("model", testModel)
                    put("messages", json.encodeToJsonElement(expectedApiMessages))
                    put("temperature", options.temperature)
                    // OMIT max_tokens and stop as they are null and explicitNulls=false
                    // put("max_tokens", JsonPrimitive(null)) // REMOVED
                    // put("stop", JsonPrimitive(null)) // REMOVED
                    put("response_format", buildJsonObject{ put("type", "text")})
                }
                val expectedJson = json.encodeToString(expectedRequestBody)

                // ... rest of the test (client creation, provider setup, execution) ...
                val client = createMockClient(expectedJson)
                val providerWithMock = OpenAIProvider(testApiKey, testBaseUrl, testModel).apply {
                    val clientField = this::class.java.getDeclaredField("client")
                    clientField.isAccessible = true
                    clientField.set(this, client)
                }
                providerWithMock.generate(history, options).toList()
            }
        }
        context("when history size is null (unlimited)") {
             val history = listOf(
                LLMMessage.SystemMessage("Original System Prompt."), // Should be used
                LLMMessage.UserMessage("User message 1"),
                LLMMessage.AssistantMessage("Assistant response 1"),
                LLMMessage.ToolResponseMessage("tool_abc", "Tool result ABC"),
                LLMMessage.UserMessage("User message 2")
            )
            val options = LLMOptions(
                systemPrompt = null, // Use history's system prompt
                historySize = null // Keep all messages
            )

            it("should send all messages including the history system prompt") {
                val expectedApiMessages = listOf(
                    mapOf("role" to "system", "content" to "Original System Prompt."),
                    mapOf("role" to "user", "content" to "User message 1"),
                    mapOf("role" to "assistant", "content" to "Assistant response 1"),
                    // Expected tool message WITHOUT tool_call_id
                    mapOf("role" to "tool", "content" to "Tool result ABC"),
                    mapOf("role" to "user", "content" to "User message 2")
                )
                val expectedRequestBody = buildJsonObject {
                    put("model", testModel)
                    put("messages", json.encodeToJsonElement(expectedApiMessages))
                    put("temperature", options.temperature)
                    // OMIT max_tokens and stop
                    // put("max_tokens", JsonPrimitive(null)) // REMOVED
                    // put("stop", JsonPrimitive(null)) // REMOVED
                    put("response_format", buildJsonObject{ put("type", "text")})
                }
                val expectedJson = json.encodeToString(expectedRequestBody)

                // ... rest of the test ...
                val client = createMockClient(expectedJson)
                val providerWithMock = OpenAIProvider(testApiKey, testBaseUrl, testModel).apply {
                    val clientField = this::class.java.getDeclaredField("client")
                    clientField.isAccessible = true
                    clientField.set(this, client)
                }
                providerWithMock.generate(history, options).toList()
            }
        }

         context("when history contains ToolCallMessage") {
            val history = listOf(
                LLMMessage.UserMessage("User message"),
                // This should be filtered out by the OpenAIProvider's conversion logic
                LLMMessage.ToolCallMessage("call_xyz", "some_tool", buildJsonObject { put("arg", "value") }),
                LLMMessage.AssistantMessage("Assistant response")
            )
            val options = LLMOptions(historySize = 5)
             val expectedApiMessages = listOf(
                 mapOf("role" to "user", "content" to "User message"),
                 mapOf("role" to "assistant", "content" to "Assistant response")
             )
             val expectedRequestBody = buildJsonObject {
                 put("model", testModel)
                 put("messages", json.encodeToJsonElement(expectedApiMessages))
                 put("temperature", options.temperature)
                 // OMIT max_tokens and stop
                 // put("max_tokens", JsonPrimitive(null)) // REMOVED
                 // put("stop", JsonPrimitive(null)) // REMOVED
                 put("response_format", buildJsonObject{ put("type", "text")})
             }
             val expectedJson = json.encodeToString(expectedRequestBody)

             // ... rest of the test ...
             val client = createMockClient(expectedJson)
             val providerWithMock = OpenAIProvider(testApiKey, testBaseUrl, testModel).apply {
                 val clientField = this::class.java.getDeclaredField("client")
                 clientField.isAccessible = true
                 clientField.set(this, client)
             }
             providerWithMock.generate(history, options).toList()
         }
    }
})
