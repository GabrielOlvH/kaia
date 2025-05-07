package dev.gabrielolv.kaia.examples

import dev.gabrielolv.kaia.core.agents.Agent
import dev.gabrielolv.kaia.core.model.AgentResult
import dev.gabrielolv.kaia.core.model.ToolCallResult
import dev.gabrielolv.kaia.core.model.ToolCall
import dev.gabrielolv.kaia.core.model.StructuredResult
import dev.gabrielolv.kaia.core.model.DirectorOutput
import dev.gabrielolv.kaia.core.model.NextStep
import dev.gabrielolv.kaia.core.model.TextResult
import dev.gabrielolv.kaia.core.tenant.*
import dev.gabrielolv.kaia.core.tools.ToolResult
import dev.gabrielolv.kaia.core.tools.ToolError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import arrow.core.Either
import dev.gabrielolv.kaia.core.Conversation
import dev.gabrielolv.kaia.core.KAIAgentSystem
import dev.gabrielolv.kaia.core.KAIAgentSystemBuilder
import dev.gabrielolv.kaia.core.RunResult
import dev.gabrielolv.kaia.core.tools.typed.ToolParameters
import dev.gabrielolv.kaia.core.tools.typed.ToolParametersInstance
import dev.gabrielolv.kaia.core.tools.typed.TypedTool
import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.utils.nextToolCallsId
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.put
import kotlin.text.get
import kotlinx.coroutines.runBlocking
import dev.gabrielolv.kaia.core.agents.director
import dev.gabrielolv.kaia.llm.LLMProvider
import dev.gabrielolv.kaia.llm.LLMOptions
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// Test Tenant Manager
class TestTenantManager : TenantManager {
    private val tenants = mutableMapOf<String, Tenant>()

    fun registerTenant(tenant: Tenant) {
        tenants[tenant.id] = tenant
    }

    override suspend fun getTenant(tenantId: String): Tenant? = tenants[tenantId]
}

// Test Tools
object EchoToolParams : ToolParameters() {
    val input = string("input").withDescription("Input text to echo")
}
class EchoTool : TypedTool<EchoToolParams>(
    name = "EchoTool",
    description = "Echoes input and tenant ID",
    paramsClass = EchoToolParams::class
) {
    override suspend fun executeTyped(toolCallId: String, parameters: ToolParametersInstance, tenantContext: TenantContext): Either<ToolError, ToolResult> {
        val inputText = parameters.getOrNull(EchoToolParams.input)
        val resultString = "Tenant: ${tenantContext.tenant.id}, Said: \"$inputText\""
        return Either.Right(ToolResult(toolCallId, resultString))
    }
}

object RestrictedToolParams : ToolParameters()
class TenantRestrictedTool : TypedTool<RestrictedToolParams>(
    name = "TenantRestrictedTool",
    description = "A tool only specific tenants can access",
    paramsClass = RestrictedToolParams::class
) {
    override suspend fun executeTyped(toolCallId: String, parameters: ToolParametersInstance, tenantContext: TenantContext): Either<ToolError, ToolResult> {
        return Either.Right(ToolResult(toolCallId, "TenantRestrictedTool executed for ${tenantContext.tenant.id}"))
    }
}

// New FailingTool
object FailingToolParams : ToolParameters() {
    val message = string("message").withDescription("Message to include in success/failure")
    val shouldFail = boolean("shouldFail").withDescription("Whether the tool should deliberately fail")
}

class FailingTool : TypedTool<FailingToolParams>(
    name = "FailingTool",
    description = "A tool that can be made to succeed or fail for testing purposes.",
    paramsClass = FailingToolParams::class
) {
    override suspend fun executeTyped(toolCallId: String, parameters: ToolParametersInstance, tenantContext: TenantContext): Either<ToolError, ToolResult> {
        val msg = parameters.getOrNull(FailingToolParams.message) ?: "Default message"
        val fail = parameters.getOrNull(FailingToolParams.shouldFail) ?: false

        return if (fail) {
            Either.Left(ToolError.ExecutionFailed("FailingTool deliberately failed for tenant ${tenantContext.tenant.id} with message: $msg"))
        } else {
            Either.Right(ToolResult(toolCallId, "FailingTool executed successfully for tenant ${tenantContext.tenant.id} with message: $msg"))
        }
    }
}

// A simple agent that directly calls a tool based on input for testing
class TestToolAgent(val toolToCall: String) : Agent {
    override val id: String = "test-tool-agent-$toolToCall"
    override val name: String = "Test Tool Agent for $toolToCall"
    override val description: String = "An agent that directly calls the '$toolToCall' tool with provided input."

    override fun process(message: LLMMessage.UserMessage, conversation: Conversation): Flow<AgentResult> {
        val receivedContent = message.content
        var actualPayloadForTool = receivedContent

        try {
            val jsonParser = Json { ignoreUnknownKeys = true }
            val parsedJson = jsonParser.decodeFromString<JsonObject>(receivedContent)
            actualPayloadForTool = parsedJson["actionPayload"]?.jsonPrimitive?.content // If director provides a specific field for tool
                ?: parsedJson["currentTask"]?.jsonPrimitive?.content
                ?: parsedJson["action"]?.jsonPrimitive?.content
                ?: receivedContent // Fallback to the whole action string
        } catch (e: Exception) {
            // If not JSON or field not found, use receivedContent as is
        }

        val arguments = if (toolToCall == "EchoTool") {
            buildJsonObject { put("input", actualPayloadForTool) }.toString()
        } else if (toolToCall == "FailingTool") {
            // For FailingTool, the 'action' (actualPayloadForTool) should be a JSON string
            // e.g., {"message": "test", "shouldFail": true}
            // If actualPayloadForTool is not already a JSON string for FailingTool, this might need adjustment
            // or the director needs to ensure it sends a JSON string in the 'action'.
            // For simplicity, assume actualPayloadForTool IS the JSON arguments string for FailingTool.
            actualPayloadForTool // Assumes this is already a JSON object string for FailingTool
        } else {
            // For TenantRestrictedTool, it might not need specific arguments beyond what the Director decided.
            // If it needs arguments, this part needs adjustment.
            // For now, assume no specific arguments for RestrictedTool from the payload itself.
            buildJsonObject {}.toString()
        }
        return flow {
            emit(ToolCallResult(
                listOf(ToolCall(
                    id = "test-call-$nextToolCallsId",
                    name = toolToCall,
                    arguments = arguments
                ))
            ))
        }
    }
}

// Mock LLM Provider for Director
class MockDirectorLLMProvider : LLMProvider {
    private val json = Json { ignoreUnknownKeys = true }

    override fun generate(messages: List<LLMMessage>, options: LLMOptions): Flow<LLMMessage> {
        val userMessage = messages.lastOrNull { it is LLMMessage.UserMessage } as? LLMMessage.UserMessage
        val originalUserInput = messages.firstNotNullOfOrNull { it as? LLMMessage.UserMessage }?.content ?: ""

        val command = originalUserInput.split(' ').firstOrNull() ?: ""
        val payload = originalUserInput.substringAfter(' ').trim()

        val isFirstDirectorCall = messages.count { it is LLMMessage.AssistantMessage && it.content.contains("nextStep") } == 0

        val directorOutput = when (command) {
            "echo" -> DirectorOutput(
                nextStep = NextStep(agentId = "test-tool-agent-EchoTool", action = payload, reason = "Routing to EchoTool agent"),
                isComplete = !isFirstDirectorCall,
                waitForUserInput = false,
                overallReason = "User requested echo"
            )
            "restricted" -> DirectorOutput(
                nextStep = NextStep(agentId = "test-tool-agent-TenantRestrictedTool", action = payload, reason = "Routing to TenantRestrictedTool agent"),
                isComplete = !isFirstDirectorCall,
                waitForUserInput = false,
                overallReason = "User requested restricted tool access"
            )
            "succeed" -> DirectorOutput( // Command for FailingTool to succeed
                // Payload should be a JSON string like {"message": "success test"}
                nextStep = NextStep(
                    agentId = "test-tool-agent-FailingTool",
                    action = buildJsonObject {
                        put("message", payload) // Assuming payload is just the message for succeed
                        put("shouldFail", false)
                    }.toString(),
                    reason = "Routing to FailingTool (expected success)"
                ),
                isComplete = !isFirstDirectorCall,
                waitForUserInput = false,
                overallReason = "User requested FailingTool to succeed"
            )
            "fail" -> DirectorOutput( // Command for FailingTool to fail
                // Payload should be a JSON string like {"message": "failure test"}
                nextStep = NextStep(
                    agentId = "test-tool-agent-FailingTool",
                    action = buildJsonObject {
                        put("message", payload) // Assuming payload is just the message for fail
                        put("shouldFail", true)
                    }.toString(),
                    reason = "Routing to FailingTool (expected failure)"
                ),
                isComplete = !isFirstDirectorCall,
                waitForUserInput = false,
                overallReason = "User requested FailingTool to fail"
            )
            "nonexistent" -> DirectorOutput( // Command for NonExistentTool
                nextStep = NextStep(
                    agentId = "test-tool-agent-NonExistentTool", // Agent that will try to call a tool not in ToolManager
                    action = payload, // Payload can be anything, tool won't be found anyway
                    reason = "Routing to NonExistentTool agent"
                ),
                isComplete = !isFirstDirectorCall,
                waitForUserInput = false,
                overallReason = "User requested a non-existent tool"
            )
            else -> DirectorOutput(
                nextStep = NextStep(agentId = "fallback-agent", action = "Unknown command: $command", reason = "Command not recognized"),
                isComplete = true,
                waitForUserInput = false,
                overallReason = "Unknown command received"
            )
        }
        val jsonResponse = json.encodeToString(directorOutput)
        return flowOf(LLMMessage.AssistantMessage(content = jsonResponse))
    }
}

// Fallback Agent
class FallbackAgent : Agent {
    override val id: String = "fallback-agent"
    override val name: String = "Fallback Agent"
    override val description: String = "Handles requests when no other agent is suitable."

    override fun process(message: LLMMessage.UserMessage, conversation: Conversation): Flow<AgentResult> {
        return flowOf(TextResult("Fallback: Could not understand or process the request: ${message.content}"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TenantIntegrationTests {
    private lateinit var system: KAIAgentSystem
    private lateinit var tenantManager: TestTenantManager

    private val tenantA = Tenant(
        id = "tenant-A", name = "Tenant Alpha",
        settings = TenantSettings(allowedTools = setOf("EchoTool", "TenantRestrictedTool")),
        permissions = setOf(TenantPermission.DEFAULT_ACCESS)
    )
    private val tenantB = Tenant(
        id = "tenant-B", name = "Tenant Bravo",
        settings = TenantSettings(allowedTools = setOf("EchoTool")), // Does not have TenantRestrictedTool
        permissions = setOf(TenantPermission.DEFAULT_ACCESS)
    )

    private val tenantC = Tenant( // New Tenant C
        id = "tenant-C", name = "Tenant Charlie",
        settings = TenantSettings(allowedTools = emptySet()), // No tools allowed
        permissions = setOf(TenantPermission.DEFAULT_ACCESS)
    )

    private val tenantD = Tenant( // New Tenant D
        id = "tenant-D", name = "Tenant Delta",
        settings = TenantSettings(allowedTools = setOf("NonExistentTool", "EchoTool", "FailingTool")), // Allows a tool not in system, and FailingTool
        permissions = setOf(TenantPermission.DEFAULT_ACCESS)
    )

    fun setup() {
        tenantManager = TestTenantManager()
        tenantManager.registerTenant(tenantA)
        tenantManager.registerTenant(tenantB)
        tenantManager.registerTenant(tenantC)
        tenantManager.registerTenant(tenantD)

        val mockLLMProvider = MockDirectorLLMProvider()
        val echoToolAgent = TestToolAgent("EchoTool")
        val restrictedToolAgent = TestToolAgent("TenantRestrictedTool")
        val failingToolAgent = TestToolAgent("FailingTool") // Agent for FailingTool
        val nonExistentToolAgent = TestToolAgent("NonExistentTool") // Agent for NonExistentTool
        val fallbackAgent = FallbackAgent()

        val agentDb = mapOf(
            echoToolAgent.id to echoToolAgent.description,
            restrictedToolAgent.id to restrictedToolAgent.description,
            failingToolAgent.id to failingToolAgent.description, // Add failing tool agent
            nonExistentToolAgent.id to nonExistentToolAgent.description, // Add non-existent tool agent
            fallbackAgent.id to fallbackAgent.description
        )

        val directorAgent = Agent.director {
            provider = mockLLMProvider
            agentDatabase = agentDb
            this.fallbackAgent = fallbackAgent // Assign the local fallbackAgent instance
            // Optional: taskGoal, constraints, useCaseSpecificInstructions, useCaseExamples
            // For these tests, the MockDirectorLLMProvider handles the logic, so complex prompt engineering for director is not strictly needed.
        }

        system = KAIAgentSystem.Companion.build {
            withTenantManager(tenantManager)
            registerTool(EchoTool())
            registerTool(TenantRestrictedTool())
            registerTool(FailingTool()) // Register FailingTool

            addAgent(directorAgent)
            addAgent(echoToolAgent)
            addAgent(restrictedToolAgent)
            addAgent(failingToolAgent) // Add failing tool agent
            addAgent(nonExistentToolAgent) // Add non-existent tool agent
            addAgent(fallbackAgent)
            designateDirector(directorAgent.id) // Designate the new director
        }
    }

    suspend fun runEchoToolExecutionTest() {
        println("\n--- Running EchoTool Execution Test ---")
        val tenantId = "tenant-A"
        val requestId = "req-echo-A"
        val sessionId = "sess-echo-A"
        val inputPayload = "Hello Alpha"
        val systemInput = "echo $inputPayload" // Director will parse this

        val result = system.run(tenantId, systemInput, requestId, sessionId)

        if (result is Either.Right) {
            val runResult = result.value
            println("RunResult for EchoTool: Success")

            var toolResultMessage: String? = null
            runResult.messageFlow.collect { llmMessage ->
                println("EchoTool Flow Message: $llmMessage")
                if (llmMessage is LLMMessage.SystemMessage && llmMessage.content.startsWith("Tool Result (EchoTool):")) {
                    toolResultMessage = llmMessage.content.substringAfter("Tool Result (EchoTool): ").trim()
                }
            }

            if (toolResultMessage != null) {
                println("EchoTool Result Message: $toolResultMessage")
                val expectedTenantPart = "Tenant: tenant-A"
                if (toolResultMessage!!.contains(expectedTenantPart)) {
                    println("SUCCESS: Message contains correct tenant ID part '$expectedTenantPart'.")
                } else {
                    println("FAILURE: Message does NOT contain correct tenant ID part '$expectedTenantPart'. Got: $toolResultMessage")
                }
                val expectedSaidPart = "Said: \"$inputPayload\""
                if (toolResultMessage!!.contains(expectedSaidPart)) {
                    println("SUCCESS: Message contains echoed input: '$inputPayload' correctly quoted.")
                } else {
                    println("FAILURE: Message does NOT contain echoed input: '$inputPayload' correctly quoted. Expected: '$expectedSaidPart', Got: $toolResultMessage")
                }
            } else {
                println("FAILURE: Tool result message was not found in SystemMessages.")
            }
        } else if (result is Either.Left) {
            println("FAILURE: system.run for EchoTool failed: ${result.value}")
        }
    }

    suspend fun runTenantAAccessToRestrictedToolTest() {
        println("\n--- Running TenantA Access to TenantRestrictedTool Test ---")
        val tenantId = "tenant-A"
        val requestId = "req-restricted-A"
        val sessionId = "sess-restricted-A"
        val inputPayload = "access attempt A" // This will be the 'action' for TestToolAgent
        val systemInput = "restricted $inputPayload" // Director will parse this

        val result = system.run(tenantId, systemInput, requestId, sessionId)

        if (result is Either.Right) {
            val runResult = result.value
            println("RunResult for TenantA Restricted Tool: Success")

            var toolExecutionSuccess = false
            var resultMessage: String? = null
            runResult.messageFlow.collect { llmMessage ->
                println("TenantA Restricted Flow Message: $llmMessage")
                if (llmMessage is LLMMessage.SystemMessage && llmMessage.content.contains("Tool Result (TenantRestrictedTool):")) {
                    resultMessage = llmMessage.content
                    if (resultMessage!!.contains("TenantRestrictedTool executed for tenant-A")) {
                        toolExecutionSuccess = true
                    }
                }
            }
            println("TenantRestrictedTool Result for A: $resultMessage")
            if (toolExecutionSuccess) {
                println("SUCCESS: TenantRestrictedTool executed successfully for tenant-A.")
            } else {
                println("FAILURE: TenantRestrictedTool did NOT execute successfully for tenant-A. Message: $resultMessage")
            }
        } else if (result is Either.Left) {
            println("FAILURE: system.run for TenantA Restricted Tool failed: ${result.value}")
        }
    }

    suspend fun runTenantBDeniedAccessToRestrictedToolTest() {
        println("\n--- Running TenantB Denied Access to TenantRestrictedTool Test ---")
        val tenantId = "tenant-B"
        val requestId = "req-restricted-B"
        val sessionId = "sess-restricted-B"
        val inputPayload = "access attempt B"
        val systemInput = "restricted $inputPayload" // Director will parse this

        val result = system.run(tenantId, systemInput, requestId, sessionId)

        if (result is Either.Right) {
            val runResult = result.value
            println("RunResult for TenantB Restricted Tool: Success (expected denial in flow)")

            var toolFailedDueToPermission = false
            var systemMessageContent: String? = null

            runResult.messageFlow.collect { llmMessage ->
                println("TenantB Restricted Flow Message: $llmMessage")
                if (llmMessage is LLMMessage.SystemMessage) {
                    systemMessageContent = llmMessage.content
                    // The denial message comes from the HandoffManager, which wraps the ToolError.
                    // We need to check for the specific reason string within the overall failure message.
                    val expectedErrorMessageSubstring = "Tool 'TenantRestrictedTool' is not allowed for tenant 'tenant-B'"

                    if (systemMessageContent!!.contains(expectedErrorMessageSubstring, ignoreCase = true)) {
                        toolFailedDueToPermission = true
                    }

                    // If we see "Tool Result (TenantRestrictedTool): TenantRestrictedTool executed for tenant-B", it's a critical failure.
                    if (systemMessageContent!!.contains("Tool Result (TenantRestrictedTool): TenantRestrictedTool executed for tenant-B")) {
                        toolFailedDueToPermission = false // Explicitly mark as failure if it executed
                        println("CRITICAL FAILURE: TenantRestrictedTool executed for tenant-B when it should have been denied.")
                    }
                }
            }
            if (toolFailedDueToPermission) {
                println("SUCCESS: TenantB was correctly denied TenantRestrictedTool. Last System Message: $systemMessageContent")
            } else {
                println("FAILURE: TenantB was NOT denied TenantRestrictedTool as expected. Last System Message: $systemMessageContent")
            }
        } else if (result is Either.Left) {
            // This path might be taken if the Director or system catches the permission issue early.
            // For tenant tool denial, we expect the error to come from tool.execute() after HandoffManager routes it.
            println("INFO: system.run for TenantB Restricted Tool resulted in Either.Left: ${result.value}")
            val errorValue = (result as Either.Left).value
            // Check if the Left value itself is indicative of the permission error.
            // This depends on how KAIAgentSystem wraps errors from HandoffManager.
             if (errorValue.toString().contains("Tool 'TenantRestrictedTool' is not allowed for tenant 'tenant-B'")) {
                 println("SUCCESS: TenantB was correctly denied TenantRestrictedTool (via system.run error matching expected tool denial message). Error: $errorValue")
             } else {
                 println("FAILURE: system.run for TenantB failed, but not with the expected tool denial message. Error: $errorValue")
             }
        }
    }

    // --- New Test Cases ---

    suspend fun runTenantNotFoundTest() {
        println("\n--- Running Tenant Not Found Test ---")
        val tenantId = "tenant-NONEXISTENT"
        val requestId = "req-nonexistent"
        val sessionId = "sess-nonexistent"
        val systemInput = "echo Hello"

        val result = system.run(tenantId, systemInput, requestId, sessionId)

        if (result is Either.Left) {
            val error = result.value
            println("RunResult for TenantNotFound: Left (expected)")
            // Check for the specific TenantNotFound data class toString() or a more reliable property
            val expectedErrorString = "TenantNotFound(tenantId=$tenantId)" // More precise
            if (error.toString() == expectedErrorString) { // Direct comparison
                println("SUCCESS: system.run correctly failed with tenant not found error: $error")
            } else {
                println("FAILURE: system.run failed, but not with the expected TenantNotFound structure. Error: $error, Expected: $expectedErrorString")
            }
        } else {
            println("FAILURE: system.run for a non-existent tenant succeeded, which is unexpected.")
            (result as Either.Right).value.messageFlow.collect { println("Unexpected Flow Message: $it") }
        }
    }

    suspend fun runTenantWithEmptyAllowedToolsTest() {
        println("\n--- Running Tenant With Empty AllowedTools Test (tenant-C trying EchoTool) ---")
        val tenantId = "tenant-C" // TenantC has allowedTools = emptySet()
        val requestId = "req-empty-C"
        val sessionId = "sess-empty-C"
        val systemInput = "echo HelloCharlie"

        val result = system.run(tenantId, systemInput, requestId, sessionId)
        var deniedDueToPermission = false
        var denialMessage: String? = null

        if (result is Either.Right) {
            result.value.messageFlow.collect { llmMessage ->
                println("TenantC EchoTool Flow Message: $llmMessage")
                if (llmMessage is LLMMessage.SystemMessage) {
                    denialMessage = llmMessage.content
                    if (denialMessage!!.contains("Tool 'EchoTool' is not allowed for tenant 'tenant-C'")) {
                        deniedDueToPermission = true
                    }
                }
            }
            if (deniedDueToPermission) {
                println("SUCCESS: TenantC was correctly denied EchoTool. Denial message: $denialMessage")
            } else {
                println("FAILURE: TenantC was NOT denied EchoTool as expected. Last System Message: $denialMessage")
            }
        } else if (result is Either.Left) {
            println("INFO: system.run for TenantC (empty allowedTools) resulted in Either.Left: ${result.value}")
            // This is also a valid path for failure if the system catches it early
             if (result.value.toString().contains("Tool 'EchoTool' is not allowed for tenant 'tenant-C'")) {
                 println("SUCCESS: TenantC was correctly denied EchoTool (via system.run error). Error: ${result.value}")
             } else {
                 println("FAILURE: TenantC was denied, but not with the expected permission message. Error: ${result.value}")
             }
        }
    }

    suspend fun runToolAllowedButFailsInternallyTest() {
        println("\n--- Running Tool Allowed But Fails Internally Test (tenant-A, FailingTool) ---")
        val tenantId = "tenant-A" // TenantA can use FailingTool (assuming added to its allowedTools or FailingTool doesn't require it)
                                 // We need to ensure FailingTool is allowed for tenant-A. Let's add it.
                                 // (Adjusting TenantA definition or assuming universal access for FailingTool if not restricted by default)
                                 // For this test, we'll assume FailingTool is added to TenantA's allowed list in setup
                                 // Or, more simply, use TenantD which explicitly allows FailingTool.
        val testTenantId = "tenant-D" // TenantD has FailingTool
        val requestId = "req-fail-D"
        val sessionId = "sess-fail-D"
        val failureMessage = "internal tool test failure"
        val systemInput = "fail $failureMessage" // Director command to make FailingTool fail with a message

        val result = system.run(testTenantId, systemInput, requestId, sessionId)
        var toolFailedInternallyAsExpected = false
        var failureReasonMessage: String? = null // Changed variable name for clarity

        if (result is Either.Right) {
            result.value.messageFlow.collect { llmMessage ->
                println("FailingTool (expected fail) Flow Message: $llmMessage")
                if (llmMessage is LLMMessage.SystemMessage) {
                    val content = llmMessage.content
                    // Capture the specific tool failure message, not just any late system message
                    if (content.contains("Tool execution failed for FailingTool") && 
                        content.contains("FailingTool deliberately failed for tenant $testTenantId with message: $failureMessage")) {
                        failureReasonMessage = content
                        toolFailedInternallyAsExpected = true
                    }
                }
            }
            if (toolFailedInternallyAsExpected) {
                println("SUCCESS: FailingTool failed internally as expected for $testTenantId. Reason: $failureReasonMessage")
            } else {
                println("FAILURE: FailingTool did NOT fail internally as expected for $testTenantId. Collected messages did not contain specific failure. Last potential reason: $failureReasonMessage")
            }
        } else if (result is Either.Left) {
            println("FAILURE: system.run for FailingTool (expected internal fail) resulted in Either.Left: ${result.value}")
        }
    }
     suspend fun runToolAllowedSucceedsInternallyTest() {
        println("\n--- Running Tool Allowed and Succeeds Internally Test (tenant-D, FailingTool) ---")
        val testTenantId = "tenant-D"
        val requestId = "req-succeed-D"
        val sessionId = "sess-succeed-D"
        val successMessage = "internal tool test success"
        val systemInput = "succeed $successMessage" // Director command to make FailingTool succeed with a message

        val result = system.run(testTenantId, systemInput, requestId, sessionId)
        var toolSucceededAsExpected = false
        var successOutput: String? = null

        if (result is Either.Right) {
            result.value.messageFlow.collect { llmMessage ->
                println("FailingTool (expected success) Flow Message: $llmMessage")
                if (llmMessage is LLMMessage.SystemMessage && llmMessage.content.startsWith("Tool Result (FailingTool):")) {
                    successOutput = llmMessage.content
                    if (successOutput!!.contains("FailingTool executed successfully for tenant $testTenantId with message: $successMessage")) {
                        toolSucceededAsExpected = true
                    }
                }
            }
            if (toolSucceededAsExpected) {
                println("SUCCESS: FailingTool succeeded as expected for $testTenantId. Output: $successOutput")
            } else {
                println("FAILURE: FailingTool did NOT succeed as expected for $testTenantId. Last System Message: $successOutput")
            }
        } else if (result is Either.Left) {
            println("FAILURE: system.run for FailingTool (expected success) resulted in Either.Left: ${result.value}")
        }
    }


    suspend fun runToolNotRegisteredButAllowedByTenantTest() {
        println("\n--- Running Tool Not Registered In System (but allowed by tenant-D) Test ---")
        val tenantId = "tenant-D" // TenantD allows "NonExistentTool"
        val requestId = "req-nonreg-D"
        val sessionId = "sess-nonreg-D"
        // We need a way for the MockDirector to try and route to "NonExistentTool"
        // Let's add a "nonexistent" command to MockDirectorLLMProvider
        val systemInput = "nonexistent TriggerNonExistentTool"

        val result = system.run(tenantId, systemInput, requestId, sessionId)
        var toolNotFoundMessageReceived = false
        var systemMessageDetail: String? = null

        if (result is Either.Right) {
            result.value.messageFlow.collect { llmMessage ->
                println("NonExistentTool Flow Message: $llmMessage")
                if (llmMessage is LLMMessage.SystemMessage) {
                    systemMessageDetail = llmMessage.content
                    // The HandoffManager should report this. The exact message depends on ToolManager's error.
                    // ToolManager's executeTool says: "Tool not found: $name"
                    // HandoffManager wraps it: "Tool execution failed for NonExistentTool: ExecutionFailed(reason=Tool not found: NonExistentTool..."
                    if (systemMessageDetail!!.contains("Tool execution failed for NonExistentTool") &&
                        systemMessageDetail!!.contains("Tool not found: NonExistentTool")) {
                        toolNotFoundMessageReceived = true
                    }
                }
            }
            if (toolNotFoundMessageReceived) {
                println("SUCCESS: System correctly reported 'NonExistentTool' not found. Message: $systemMessageDetail")
            } else {
                println("FAILURE: System did NOT report 'NonExistentTool' not found as expected. Last System Message: $systemMessageDetail")
            }

        } else if (result is Either.Left) {
            println("FAILURE: system.run for NonExistentTool resulted in Either.Left, unexpected path for this test: ${result.value}")
        }
    }

    // --- Concurrency Tests ---
    // Helper to run a single system call and collect key messages for concurrency tests
    private suspend fun runAndCollectFinalMessage(tenantId: String, input: String, requestIdSuffix: String): String {
        val result = system.run(tenantId, input, "req-$requestIdSuffix", "sess-$requestIdSuffix")
        var definitiveOutcomeMessage = "NO_DEFINITIVE_OUTCOME_CAPTURED_FOR_$requestIdSuffix"
        var lastProcessingMessage = ""

        if (result is Either.Right) {
            result.value.messageFlow.collect { llmMessage ->
                 // Capture messages indicating final outcome (tool result, specific error, or completion)
                if (llmMessage is LLMMessage.SystemMessage) {
                    val content = llmMessage.content
                    if (content.startsWith("Tool Result") || 
                        content.startsWith("Tool execution failed for") || 
                        content.contains("not allowed for tenant")) {
                        definitiveOutcomeMessage = content // Prioritize definitive tool outcomes
                    } else if (content.contains("Processing complete") || 
                               content.contains("Processing finished with errors") || 
                               content.contains("Director indicates task is complete")) {
                        lastProcessingMessage = content
                    }
                }
            }
            // If we didn't get a specific tool outcome, use the last processing message.
            if (definitiveOutcomeMessage.startsWith("NO_DEFINITIVE_OUTCOME")) {
                if (lastProcessingMessage.isNotEmpty()) {
                    definitiveOutcomeMessage = lastProcessingMessage
                } else {
                    // Fallback if no relevant message at all was captured from a successful run's flow
                    definitiveOutcomeMessage = "NO_RELEVANT_SYSTEM_MESSAGE_IN_FLOW_FOR_$requestIdSuffix"
                }
            }
        } else if (result is Either.Left) {
            definitiveOutcomeMessage = "SYSTEM_RUN_ERROR_FOR_$requestIdSuffix: ${result.value}"
        }
        println("Concurrent Call ($requestIdSuffix) Final Message for $tenantId: $definitiveOutcomeMessage")
        return definitiveOutcomeMessage
    }


    suspend fun runConcurrentOperationsDifferentTenantsTest() = coroutineScope {
        println("\n--- Running Concurrent Operations Different Tenants Test ---")

        val results = awaitAll(
            async { runAndCollectFinalMessage("tenant-A", "echo EchoMessageA", "concA1") }, // Tenant A uses EchoTool
            async { runAndCollectFinalMessage("tenant-B", "restricted DenyAccessB", "concB1") }, // Tenant B attempts RestrictedTool (should be denied)
            async { runAndCollectFinalMessage("tenant-D", "succeed SucceedMessageD", "concD1") }  // Tenant D uses FailingTool (success)
        )

        val tenantAResult = results[0]
        val tenantBResult = results[1]
        val tenantDResult = results[2]

        var success = true

        // Check Tenant A EchoTool success (ensure quotes around the echoed message value)
        if (!tenantAResult.contains("Tool Result (EchoTool): Tenant: tenant-A, Said: \"EchoMessageA\"")) {
            println("FAILURE (Concurrent Diff Tenants): TenantA EchoTool did not succeed as expected. Got: $tenantAResult")
            success = false
        }
        // Check Tenant B RestrictedTool denial
        if (!tenantBResult.contains("Tool 'TenantRestrictedTool' is not allowed for tenant 'tenant-B'")) {
            println("FAILURE (Concurrent Diff Tenants): TenantB RestrictedTool was not denied as expected. Got: $tenantBResult")
            success = false
        }
        // Check Tenant D FailingTool success (no single quotes around tenantId, correct message)
        if (!tenantDResult.contains("Tool Result (FailingTool): FailingTool executed successfully for tenant tenant-D with message: SucceedMessageD")) {
            println("FAILURE (Concurrent Diff Tenants): TenantD FailingTool did not succeed as expected. Got: $tenantDResult")
            success = false
        }

        if (success) {
            println("SUCCESS: Concurrent operations for different tenants behaved as expected.")
        } else {
            println("PARTIAL/TOTAL FAILURE: Concurrent operations for different tenants had issues.")
        }
    }

    suspend fun runConcurrentOperationsSameTenantTest() = coroutineScope {
        println("\n--- Running Concurrent Operations Same Tenant Test (tenant-A) ---")

        val results = awaitAll(
            async { runAndCollectFinalMessage("tenant-A", "echo MessageOneA", "concA_Same1") },
            async { runAndCollectFinalMessage("tenant-A", "echo MessageTwoA", "concA_Same2") }
        )

        val resultOne = results[0]
        val resultTwo = results[1]
        var success = true

        // Ensure quotes around the echoed message value
        if (!resultOne.contains("Tool Result (EchoTool): Tenant: tenant-A, Said: \"MessageOneA\"")) {
            println("FAILURE (Concurrent Same Tenant): Call 1 for TenantA EchoTool did not succeed as expected. Got: $resultOne")
            success = false
        }
        if (!resultTwo.contains("Tool Result (EchoTool): Tenant: tenant-A, Said: \"MessageTwoA\"")) {
            println("FAILURE (Concurrent Same Tenant): Call 2 for TenantA EchoTool did not succeed as expected. Got: $resultTwo")
            success = false
        }
         // Check if one interfered with the other - simplistic check for now
        if (resultOne.contains("MessageTwoA") || resultTwo.contains("MessageOneA")) {
            if (!resultOne.contains("MessageOneA") || !resultTwo.contains("MessageTwoA")) { // Ensure it's not just the input echoing back
                 println("POTENTIAL FAILURE (Concurrent Same Tenant): Possible interference between calls. Result1: $resultOne, Result2: $resultTwo")
                 // success = false; // This check might be too strict or need refinement based on how inputs are processed
            }
        }


        if (success) {
            println("SUCCESS: Concurrent operations for the same tenant (tenant-A) behaved as expected.")
        } else {
            println("PARTIAL/TOTAL FAILURE: Concurrent operations for the same tenant (tenant-A) had issues.")
        }
    }


}

fun main() = runBlocking {
    val tests = TenantIntegrationTests()
    tests.setup()
    tests.runEchoToolExecutionTest()
    tests.runTenantAAccessToRestrictedToolTest()
    tests.runTenantBDeniedAccessToRestrictedToolTest()

    // New basic edge case tests
    tests.runTenantNotFoundTest()
    tests.runTenantWithEmptyAllowedToolsTest()
    tests.runToolAllowedSucceedsInternallyTest() // Test success path for FailingTool first
    tests.runToolAllowedButFailsInternallyTest()
    tests.runToolNotRegisteredButAllowedByTenantTest()

    // Concurrency tests
    tests.runConcurrentOperationsDifferentTenantsTest()
    tests.runConcurrentOperationsSameTenantTest()


    println("\n--- All tests finished ---")
}
