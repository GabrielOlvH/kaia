package dev.gabrielolv.kaia

import dev.gabrielolv.kaia.core.HandoffManager
import dev.gabrielolv.kaia.core.Message
import dev.gabrielolv.kaia.core.Orchestrator
import dev.gabrielolv.kaia.core.StepStatus
import dev.gabrielolv.kaia.core.agents.Agent
import dev.gabrielolv.kaia.core.agents.llm // Assuming you have this extension function
import dev.gabrielolv.kaia.core.agents.withWorkflowPlanner // Import the new planner
import dev.gabrielolv.kaia.core.tools.ToolManager
import dev.gabrielolv.kaia.core.tools.ToolResult
import dev.gabrielolv.kaia.core.tools.builders.createTool
import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class WorkflowExecutionTests : FunSpec({

    // --- Test Setup ---
    val toolManager = ToolManager()
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Define Calculator Tool (assuming CalculatorParams exists)
    val calculatorTool = createTool<CalculatorParams> {
        name = "calculator"
        description = "Performs basic arithmetic calculations (add, subtract, multiply, divide)."

        execute { params ->
            val b = params[CalculatorParams.b]
            val a = params[CalculatorParams.a]
            val result = when (params[CalculatorParams.operation]) {
                "add" -> a + b
                "subtract" -> a - b
                "multiply" -> a * b
                "divide" -> if (b != 0.0) a / b else Double.NaN // Indicate error differently
                else -> Double.NaN // Indicate error
            }

            if (result.isNaN()) {
                ToolResult(
                    success = false,
                    result = if (params[CalculatorParams.operation] == "divide" && b == 0.0) "Error: Division by zero" else "Unknown operation: ${params[CalculatorParams.operation]}"
                )
            } else {
                ToolResult(success = true, result = result.toString())
            }
        }
    }
    toolManager.registerTool(calculatorTool)

    // Create LLM provider (ensure API key is set as environment variable)
    val apiKey = System.getenv("GROQ_KEY")
    if (apiKey.isNullOrBlank()) {
        println("Warning: GROQ_KEY environment variable not set. Tests requiring LLM will fail.")
    }
    val llmProvider = LLMProvider.openAI(
        apiKey = apiKey ?: "dummy-key", // Use dummy key if not set to avoid crash
        baseUrl = "https://api.groq.com/openai/v1",
        model = "llama3-70b-8192", // Updated model name if needed
        toolManager = toolManager
    )

    val reasoningProvider = LLMProvider.openAI(
        apiKey = apiKey ?: "dummy-key", // Use dummy key if not set to avoid crash
        baseUrl = "https://api.groq.com/openai/v1",
        model = "deepseek-r1-distill-llama-70b", // Updated model name if needed
    )

    // --- Agent Definitions ---
    val defaultCustomerServiceAgent = Agent.llm(
        provider = llmProvider,
        systemPrompt = "You are a helpful general customer service assistant. Handle basic greetings and simple queries. If a task is complex or requires specific expertise (billing, tech support, sales, calculations), explain that it needs routing."
    ) {
        id = "customer_service_default"
        name = "Default Customer Service"
        description = "Handles basic greetings and simple queries. Cannot perform complex tasks directly."
    }

    val billingAgent = Agent.llm(
        provider = llmProvider,
        systemPrompt = "You are a billing specialist. You can answer questions about invoices, payments, and subscriptions. Use the calculator tool if needed for calculations related to billing."
    ) {
        id = "billing_specialist"
        name = "Billing Specialist"
        description = "Handles billing, payments, subscription inquiries, and can use a calculator."
        // Add the tool to this agent if needed directly, or rely on planner prompt
        // tools = listOf(calculatorTool) // Example if agent uses tool directly
    }

    val techSupportAgent = Agent.llm(
        provider = llmProvider,
        systemPrompt = "You are a technical support specialist. Help users troubleshoot issues with applications, connectivity, and hardware."
    ) {
        id = "tech_support"
        name = "Tech Support"
        description = "Handles technical issues and troubleshooting."
    }

    val salesAgent = Agent.llm(
        provider = llmProvider,
        systemPrompt = "You are a sales representative. Answer product inquiries, explain features, discuss upgrades, and handle new purchases."
    ) {
        id = "sales_rep"
        name = "Sales Representative"
        description = "Handles product inquiries, upgrades, and new purchases."
    }

    // --- Orchestrator and HandoffManager Setup ---
    val orchestrator = Orchestrator()
    orchestrator.addAgent(defaultCustomerServiceAgent)
    orchestrator.addAgent(billingAgent)
    orchestrator.addAgent(techSupportAgent)
    orchestrator.addAgent(salesAgent)
    val handoffManager = HandoffManager(
        orchestrator = orchestrator,
        provider = reasoningProvider // Pass provider for verification step
    )
    val plannerAgent = Agent.withWorkflowPlanner(
        handoffManager = handoffManager, // Temp manager needed for DB access, ID will be set below
        provider = reasoningProvider,
        agentDatabase = orchestrator.getAgentDatabase(), // Get agents from orchestrator
        defaultAgent = defaultCustomerServiceAgent, // Specify the default
        json = json
    )
    // Now add the *actual* planner agent to the orchestrator
    orchestrator.addAgent(plannerAgent)


    // Create the Planner Agent
    // Note: The planner itself doesn't need a specific system prompt for *its own* execution,
    // its behavior is defined by the prompt within `withWorkflowPlanner`.


    // Create the HandoffManager (now Workflow Executor)
    // Pass the *actual* planner agent's ID


    // --- Test Cases ---

    test("Simple Request - Should use Default Agent (No Workflow)") {
        runBlocking {
            if (apiKey.isNullOrBlank()) return@runBlocking // Skip if no API key

            val conversationId = handoffManager.startConversation()
            val simpleMessage = Message(
                sender = "user",
                content = "Hello, how are you today?",
                conversationId = conversationId
            )

            println("\n===== TEST: Simple Request =====")
            val responses = mutableListOf<LLMMessage>()
            handoffManager.sendMessage(conversationId, simpleMessage, plannerAgent.id)?.collect {
                println("Received: $it")
                responses.add(it)
            }

            // Verification
            responses shouldNotBe null

            // Check if the default agent actually responded
            responses.filterIsInstance<LLMMessage.AssistantMessage>().size shouldBeGreaterThan 0
            // Check that no workflow steps were executed
            val conversation = handoffManager.getConversation(conversationId)
            conversation?.currentWorkflow shouldBe null
            conversation?.currentStepIndex shouldBe -1
        }
    }

    test("Complex Request - Should Trigger Multi-Step Workflow (Billing -> Tech)") {
        runBlocking {
            if (apiKey.isNullOrBlank()) return@runBlocking // Skip if no API key

            val conversationId = handoffManager.startConversation()
            // Requires billing to check invoice (maybe use calculator) AND tech support for crash
            val complexMessage = Message(
                sender = "user",
                content = "I think I was double-charged on my last invoice (Invoice #12345), can you check the total? Also, my app is crashing when I upload files.",
                conversationId = conversationId
            )

            println("\n===== TEST: Complex Request (Billing -> Tech) =====")
            val responses = mutableListOf<LLMMessage>()
            handoffManager.sendMessage(conversationId, complexMessage, plannerAgent.id)?.catch { err -> err.printStackTrace() }?.collect {
                println("Received: $it")
                responses.add(it)
            }

            // Verification
            responses shouldNotBe null
            // Planning happened
            responses.filterIsInstance<LLMMessage.SystemMessage>().any {
                it.content.contains("Planning execution...")
            } shouldBe true


            // Check for execution steps
            val step1Execution = responses.filterIsInstance<LLMMessage.SystemMessage>().any {
                it.content.contains("Executing Step 1") && it.content.contains(billingAgent.id)
            }
            val step2Execution = responses.filterIsInstance<LLMMessage.SystemMessage>().any {
                it.content.contains("Executing Step 2") && it.content.contains(techSupportAgent.id)
            }
            step1Execution shouldBe true
            step2Execution shouldBe true

            // Check for responses from involved agents (can be tricky if steps only emit system messages)
            // A better check is the final workflow state
            val conversation = handoffManager.getConversation(conversationId)
            conversation?.currentWorkflow shouldNotBe null
            conversation?.currentWorkflow?.steps?.shouldHaveSize(2) // Expecting 2 steps
            conversation?.currentWorkflow?.steps?.get(0)?.agentId shouldBe billingAgent.id
            conversation?.currentWorkflow?.steps?.get(0)?.status shouldBe StepStatus.COMPLETED
            conversation?.currentWorkflow?.steps?.get(1)?.agentId shouldBe techSupportAgent.id
            conversation?.currentWorkflow?.steps?.get(1)?.status shouldBe StepStatus.COMPLETED

            // Check for final verification
            responses.filterIsInstance<LLMMessage.SystemMessage>().any {
                it.content.contains("Workflow completed successfully.")
            } shouldBe true
            responses.filterIsInstance<LLMMessage.SystemMessage>().any {
                it.content.contains("Performing final verification...")
            } shouldBe true
            responses.lastOrNull() shouldNotBe null // Should have a final verification message from assistant

        }
    }

    test("Workflow with Tool Use (Billing Calculator)") {
        runBlocking {
            if (apiKey.isNullOrBlank()) return@runBlocking // Skip if no API key

            val conversationId = handoffManager.startConversation()
            // Message specifically asking the billing agent to calculate something
            val billingCalcMessage = Message(
                sender = "user",
                content = "My base fee is $99.99 and I used 5 extra units at $5 each. Can the billing department calculate my total charge using the calculator tool?",
                conversationId = conversationId
            )

            println("\n===== TEST: Workflow with Tool Use (Billing) =====")
            val responses = mutableListOf<LLMMessage>()
            handoffManager.sendMessage(conversationId, billingCalcMessage, plannerAgent.id)?.collect {
                println("Received: $it")
                responses.add(it)
            }

            // Verification
            responses shouldNotBe null
            // Planning should identify billing agent
            responses.filterIsInstance<LLMMessage.SystemMessage>().any {
                it.content.contains("Executing Step 1") && it.content.contains(billingAgent.id)
            } shouldBe true

            // Check for Tool Call and Result within the flow for the billing agent step
            val toolCallMessage = responses.filterIsInstance<LLMMessage.ToolCallMessage>().firstOrNull()
            val toolResultMessage = responses.filterIsInstance<LLMMessage.ToolResponseMessage>().firstOrNull()

            toolCallMessage shouldNotBe null
            toolCallMessage?.name shouldBe calculatorTool.name
            // Could add more specific checks on toolCallMessage.arguments if needed

            toolResultMessage shouldNotBe null
            toolResultMessage?.content shouldContain (99.99 + 5 * 5.0).toString() // 124.99

            // Check final state
            val conversation = handoffManager.getConversation(conversationId)
            conversation?.currentWorkflow shouldNotBe null
            conversation?.currentWorkflow?.steps?.shouldHaveSize(1)
            conversation?.currentWorkflow?.steps?.get(0)?.agentId shouldBe billingAgent.id
            conversation?.currentWorkflow?.steps?.get(0)?.status shouldBe StepStatus.COMPLETED

            // Check for final verification
            responses.filterIsInstance<LLMMessage.SystemMessage>().any {
                it.content.contains("Workflow completed successfully.")
            } shouldBe true
            responses.filterIsInstance<LLMMessage.SystemMessage>().any {
                it.content.contains("Performing final verification...")
            } shouldBe true
        }
    }

    // Optional: Test for workflow failure (requires an agent designed to fail or mocking)
    test("Workflow Failure - Step Fails") {
        runBlocking {
            if (apiKey.isNullOrBlank()) return@runBlocking

            // Create a failing agent
            val failingAgent = Agent.create {
                id = "failing_agent"
                name = "Failing Agent"
                description = "This agent always fails."
                processor = { _ -> throw RuntimeException("Intentional failure for testing!") }
            }
            orchestrator.addAgent(failingAgent) // Add to orchestrator *for this test*

            // Update planner's agent database view if necessary (or recreate planner)
            val managerForFailureTest = HandoffManager(
                orchestrator = orchestrator,
                provider = reasoningProvider
            )

            val plannerAgentForFailureTest = Agent.withWorkflowPlanner(
                handoffManager = managerForFailureTest, // Manager instance is okay
                provider = reasoningProvider,
                agentDatabase = orchestrator.getAgentDatabase(), // Get updated list
                defaultAgent = defaultCustomerServiceAgent,
                json = json
            )
            orchestrator.addAgent(plannerAgentForFailureTest) // Add/replace planner

            // Need a dedicated manager instance if planner ID changes or state needs isolation

            val conversationId = managerForFailureTest.startConversation()
            // Message designed to trigger billing -> failing_agent
            val failingWorkflowMessage = Message(
                sender = "user",
                content = "Check my invoice total for #5678 and then run the failing process.",
                conversationId = conversationId
            )

            println("\n===== TEST: Workflow Failure =====")
            val responses = mutableListOf<LLMMessage>()
            // Use the dedicated manager instance for this test
            managerForFailureTest.sendMessage(conversationId, failingWorkflowMessage, plannerAgentForFailureTest.id)?.catch { err -> err.printStackTrace() }?.collect {
                println("Received: $it")
                responses.add(it)
            }

            // Verification
            responses shouldNotBe null
            // Step 1 (Billing) should likely complete


            // Step 2 (Failing) should start and then error out
            responses.filterIsInstance<LLMMessage.SystemMessage>().any {
                it.content.contains("Executing Step 2") && it.content.contains(failingAgent.id)
            } shouldBe true
            responses.filterIsInstance<LLMMessage.SystemMessage>().any {
                it.content.contains("Workflow Error (Step 2)") && it.content.contains("Intentional failure")
            } shouldBe true
            responses.filterIsInstance<LLMMessage.SystemMessage>().any {
                it.content.contains("Workflow finished with errors")
            } shouldBe true

            // Check final workflow state
            val conversation = managerForFailureTest.getConversation(conversationId)
            conversation?.currentWorkflow shouldNotBe null
            // Planner might generate more steps, but only up to the failure should run
            conversation?.currentWorkflow?.steps?.find { it.agentId == billingAgent.id }?.status shouldBe StepStatus.COMPLETED
            conversation?.currentWorkflow?.steps?.find { it.agentId == failingAgent.id }?.status shouldBe StepStatus.FAILED
            conversation?.currentWorkflow?.steps?.find { it.agentId == failingAgent.id }?.error shouldContain "Intentional failure"

            // Ensure final verification did NOT run
            responses.filterIsInstance<LLMMessage.SystemMessage>().none {
                it.content.contains("Performing final verification...")
            } shouldBe true

            // Clean up the failing agent if necessary
            orchestrator.removeAgent(failingAgent.id)
            orchestrator.removeAgent(plannerAgentForFailureTest.id) // Remove the test-specific planner
        }
    }
})
