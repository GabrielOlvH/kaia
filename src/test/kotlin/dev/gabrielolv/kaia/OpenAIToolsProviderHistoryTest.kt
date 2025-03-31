package dev.gabrielolv.kaia

import dev.gabrielolv.kaia.core.tools.ToolManager
import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.providers.OpenAIToolsProvider
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
class OpenAIToolsProviderHistoryTest : DescribeSpec({

    val testApiKey = "test-key-tools"
    val testBaseUrl = "http://localhost/v1"
    val testModel = "gpt-test-tools"

    // Mock ToolManager - replace with your actual mocking strategy if needed
    val mockToolManager = ToolManager() // Assuming a simple constructor or use a mock library

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
        prettyPrint = false
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    // Mock client helper (similar to OpenAIProvider's test)
    fun createMockClient(
        expectedRequestBodyJson: String,
        dummyApiResponseJson: String = """
            {
                "id": "chatcmpl-456",
                "object": "chat.completion",
                "created": 1677652290,
                "model": "$testModel",
                "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "Final mock response after history."},
                    "finish_reason": "stop"
                }],
                "usage": {"prompt_tokens": 50, "completion_tokens": 10, "total_tokens": 60}
            }
        """.trimIndent()
    ): HttpClient {
        val mockEngine = MockEngine { request ->
            request.method shouldBe HttpMethod.Post
            request.url.toString() shouldBe "$testBaseUrl/chat/completions"
            request.headers[HttpHeaders.Authorization] shouldBe "Bearer $testApiKey"

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
                json(json)
            }
        }
    }

    // --- Test Cases ---

    describe("OpenAIToolsProvider history handling") {

        context("when history includes tool calls and responses") {
            val history = listOf(
                LLMMessage.UserMessage("Find the weather in London and summarize it."), // Keep
                LLMMessage.ToolCallMessage( // Keep (as assistant requesting tool)
                    id = "call_abc",
                    name = "get_weather",
                    arguments = buildJsonObject { put("location", "London") }
                ),
                LLMMessage.ToolResponseMessage( // Keep (as tool result)
                    toolCallId = "call_abc",
                    content = """{"temperature": 15, "unit": "C", "description": "Cloudy"}"""
                ),
                 LLMMessage.AssistantMessage("The weather in London is 15°C and cloudy."), // Keep (final summary)
                 LLMMessage.UserMessage("Thanks!") // Keep (latest)
            )
            val options = LLMOptions(
                systemPrompt = "You are a helpful assistant.",
                historySize = 4 // System + last 4 messages
            )

            it("should format history with assistant tool calls and tool responses correctly") {
                // Expected messages sent TO the API
                val expectedApiMessages = buildJsonArray {
                    addJsonObject { // System Prompt
                        put("role", "system")
                        put("content", "You are a helpful assistant.")
                    }
                    addJsonObject { // Assistant requesting tool call
                        put("role", "assistant")
                        put("content", JsonPrimitive(null)) // Content should be null
                        putJsonArray("tool_calls") {
                            addJsonObject {
                                put("id", "call_abc")
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", "get_weather")
                                    // Arguments must be a JSON *string*
                                    put("arguments", """{"location":"London"}""")
                                }
                            }
                        }
                    }
                    addJsonObject { // Tool response
                        put("role", "tool")
                        put("tool_call_id", "call_abc")
                        put("content", """{"temperature": 15, "unit": "C", "description": "Cloudy"}""")
                    }
                     addJsonObject { // Assistant final summary
                        put("role", "assistant")
                        put("content", "The weather in London is 15°C and cloudy.")
                    }
                    addJsonObject { // Latest User message
                        put("role", "user")
                        put("content", "Thanks!")
                    }
                }

                val expectedRequestBody = buildJsonObject {
                    put("model", testModel)
                    put("messages", expectedApiMessages)
                    put("temperature", options.temperature)
                    // Tools parameter should be included if ToolManager has tools
                    // For this test, assume ToolManager is empty or tools aren't relevant to history formatting itself
                    // put("tools", buildJsonArray { ... })
                    put("max_tokens", JsonPrimitive(null))
                    put("response_format", buildJsonObject{ put("type", "text")})

                }
                val expectedJson = json.encodeToString(expectedRequestBody)

                val client = createMockClient(expectedJson)
                // Assume OpenAIToolsProvider can accept a client for testing
                val providerWithMock = OpenAIToolsProvider(testApiKey, testBaseUrl, testModel, mockToolManager).apply {
                     val clientField = this::class.java.getDeclaredField("client")
                    clientField.isAccessible = true
                    clientField.set(this, client)
                }

                // We only care about the request sent, not the response processing here
                providerWithMock.generate(history, options).toList()
            }
        }

         context("when history trimming cuts off tool interactions") {
            val history = listOf(
                LLMMessage.UserMessage("User 1"),
                LLMMessage.ToolCallMessage("call_1", "tool_a", buildJsonObject{}),
                LLMMessage.ToolResponseMessage("call_1", "Result A"),
                LLMMessage.UserMessage("User 2"), // Keep
                LLMMessage.ToolCallMessage("call_2", "tool_b", buildJsonObject{}), // Keep
                LLMMessage.ToolResponseMessage("call_2", "Result B"), // Keep
                LLMMessage.UserMessage("User 3") // Keep
            )
            val options = LLMOptions(historySize = 4) // Keep last 4

            it("should send only the latest messages, maintaining tool call/response structure") {
                 val expectedApiMessages = buildJsonArray {
                    // User 1, Assistant(call_1), ToolResponse(call_1) are trimmed
                     addJsonObject { // User 2
                        put("role", "user")
                        put("content", "User 2")
                    }
                    addJsonObject { // Assistant requesting tool_b
                        put("role", "assistant")
                        put("content", JsonPrimitive(null))
                        putJsonArray("tool_calls") {
                            addJsonObject {
                                put("id", "call_2")
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", "tool_b")
                                    put("arguments", "{}")
                                }
                            }
                        }
                    }
                    addJsonObject { // Tool response B
                        put("role", "tool")
                        put("tool_call_id", "call_2")
                        put("content", "Result B")
                    }
                    addJsonObject { // User 3
                        put("role", "user")
                        put("content", "User 3")
                    }
                }
                 val expectedRequestBody = buildJsonObject {
                    put("model", testModel)
                    put("messages", expectedApiMessages)
                    put("temperature", options.temperature)
                    put("max_tokens", JsonPrimitive(null))
                    put("response_format", buildJsonObject{ put("type", "text")})
                }
                val expectedJson = json.encodeToString(expectedRequestBody)

                val client = createMockClient(expectedJson)
                val providerWithMock = OpenAIToolsProvider(testApiKey, testBaseUrl, testModel, mockToolManager).apply {
                     val clientField = this::class.java.getDeclaredField("client")
                    clientField.isAccessible = true
                    clientField.set(this, client)
                }

                providerWithMock.generate(history, options).toList()
            }
        }

        context("when history contains internal ToolCallMessage") {
             val history = listOf(
                LLMMessage.UserMessage("User message"),
                // This internal representation should be filtered out
                LLMMessage.ToolCallMessage("call_internal", "some_tool", buildJsonObject{}),
                LLMMessage.AssistantMessage("Assistant response")
            )
            val options = LLMOptions(historySize = 5)

            it("should exclude internal ToolCallMessage from the API request") {
                 val expectedApiMessages = buildJsonArray {
                    addJsonObject { put("role", "user"); put("content", "User message") }
                    // ToolCallMessage is omitted
                    addJsonObject { put("role", "assistant"); put("content", "Assistant response") }
                }
                 val expectedRequestBody = buildJsonObject {
                    put("model", testModel)
                    put("messages", expectedApiMessages)
                    put("temperature", options.temperature)
                    put("max_tokens", JsonPrimitive(null))
                    put("response_format", buildJsonObject{ put("type", "text")})
                }
                val expectedJson = json.encodeToString(expectedRequestBody)

                val client = createMockClient(expectedJson)
                val providerWithMock = OpenAIToolsProvider(testApiKey, testBaseUrl, testModel, mockToolManager).apply {
                     val clientField = this::class.java.getDeclaredField("client")
                    clientField.isAccessible = true
                    clientField.set(this, client)
                }

                providerWithMock.generate(history, options).toList()
            }
        }
    }
})
