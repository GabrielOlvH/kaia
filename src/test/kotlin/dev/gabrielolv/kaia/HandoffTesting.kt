package dev.gabrielolv.kaia

import dev.gabrielolv.kaia.core.HandoffManager
import dev.gabrielolv.kaia.core.Message
import dev.gabrielolv.kaia.core.Orchestrator
import dev.gabrielolv.kaia.core.agents.Agent
import dev.gabrielolv.kaia.core.agents.llm
import dev.gabrielolv.kaia.core.agents.withHandoff
import dev.gabrielolv.kaia.core.tools.ToolManager
import dev.gabrielolv.kaia.core.tools.ToolResult
import dev.gabrielolv.kaia.core.tools.builders.createTool
import dev.gabrielolv.kaia.llm.LLMProvider
import dev.gabrielolv.kaia.utils.nextThreadId
import io.kotest.core.spec.style.FunSpec

class HandoffTesting : FunSpec({

    test("Smart Handoff Test") {

        val toolManager = ToolManager()

        val calculatorTool = createTool<CalculatorParams> {
            name = "calculator"
            description = "Performs basic arithmetic calculations"

            execute { params ->
                val b = params[CalculatorParams.b]
                val a = params[CalculatorParams.a]
                val result = when (params[CalculatorParams.operation]) {
                    "add" -> a + b
                    "subtract" -> a - b
                    "multiply" -> a * b
                    "divide" -> if (b != 0.0) a / b else "Error: Division by zero"
                    else -> "Unknown operation: ${params[CalculatorParams.operation]}"
                }

                ToolResult(
                    success = true,
                    result = result.toString()
                )
            }
        }

        toolManager.registerTool(calculatorTool)

        // Create LLM provider
        val openAI = LLMProvider.openAI(
            apiKey = System.getenv("GROQ_KEY"),
            baseUrl = "https://api.groq.com/openai/v1",
            model = "llama-3.3-70b-specdec",
            toolManager = toolManager
        )

        // Create orchestrator
        val orchestrator = Orchestrator()

        // Create handoff manager
        val handoffManager = HandoffManager(orchestrator, "customer_service")

        // Create a conversation
        val conversationId = nextThreadId

        // Create agents with different specializations
        val customerServiceAgent = Agent.withHandoff(
            handoffManager = handoffManager,
            conversationId = conversationId,
            provider = openAI,
            agentDatabase = mapOf(
                "customer_service" to "General customer service inquiries and routing",
                "billing_specialist" to "Handles billing, payments, and subscription inquiries",
                "tech_support" to "Handles technical issues and troubleshooting",
                "sales_rep" to "Handles product inquiries, upgrades, and new purchases"
            ),
            systemPrompt = "You are a customer service representative. Be polite and helpful."
        ) {
            id = "customer_service"
            name = "Customer Service Agent"
            description = "General customer service inquiries and routing"
        }

        val billingSpecialist = Agent.llm(
            provider = openAI,
            systemPrompt = "You are in the billing department;"
        )  {
            id = "billing_specialist"
            name = "Billing Specialist"
            description = "Handles billing, payments, and subscription inquiries"
        }


        val techSupport = Agent.llm(
            provider = openAI,
            systemPrompt = "Handles technical issues and troubleshooting"
        ) {
            id = "tech_support"
            name = "Tech Support"
            description = "Handles technical issues and troubleshooting"
        }

        val salesRep = Agent.llm(
            provider = openAI,
            systemPrompt = "Handles product inquiries, upgrades, and new purchases"
        )  {
            id = "sales_rep"
            name = "Sales Representative"
            description = "Handles product inquiries, upgrades, and new purchases"
        }

        toolManager.errorHandler = { tool, result ->
            orchestrator.processWithAgent("explainer", Message(content = """
                Tool Definition:
                Tool Name: ${tool.name}
                Tool Desc: ${tool.description}
                Parameters: 
                ${tool.parameterSchema}
                
                Result:
                ${result}
            """.trimIndent())).collect {
                println(it)
            }
        }

        // Register all agents with the orchestrator
        orchestrator.addAgent(customerServiceAgent)
        orchestrator.addAgent(billingSpecialist)
        orchestrator.addAgent(techSupport)
        orchestrator.addAgent(salesRep)

        orchestrator.addAgent(Agent.llm(openAI, systemPrompt = "There was an error while executing the following tool. Your job is to explain in a simple manner to the user what happened.") {
            name = "Explainer"
            id = "explainer"
        })

        handoffManager.startConversation(
            conversationId = conversationId,
            initialAgentId = "customer_service"
        )
        // Simulate a conversation with automatic handoffs

        // Initial general inquiry
        println("===== CONVERSATION START =====")
        val initialMessage = Message(
            sender = "user",
            content = "Hi there, I'm interested in your services."
        )

        handoffManager.sendMessage(conversationId, initialMessage)?.collect { response ->
            println("User: ${initialMessage.content}")
            println("${handoffManager.getConversation(conversationId)?.currentAgentId}: ${response}")
            println()
        }

        // Billing question that should trigger handoff
        val billingMessage = Message(
            sender = "user",
            content = "I have a question about my last invoice. I think I was charged twice. Can you check using your calculator tool what's 0 + 10?"
        )

        handoffManager.sendMessage(conversationId, billingMessage)?.collect { response ->
            println("User: ${billingMessage.content}")
            println("${handoffManager.getConversation(conversationId)?.currentAgentId}: ${response}")
            println()
        }
        // Technical question that should trigger another handoff
        val techMessage = Message(
            sender = "user",
            content = "My application keeps crashing when I try to upload large files."
        )

         handoffManager.sendMessage(conversationId, techMessage)?.collect { response ->
             println("User: ${techMessage.content}")
             println("${handoffManager.getConversation(conversationId)?.currentAgentId}: ${response}")
             println()
         }

        // Sales inquiry that should trigger yet another handoff
        val salesMessage = Message(
            sender = "user",
            content = "I'd like to upgrade to your premium plan. What features would I get?"
        )

        handoffManager.sendMessage(conversationId, salesMessage)?.collect { response ->
            println("User: ${salesMessage.content}")
            println("${handoffManager.getConversation(conversationId)?.currentAgentId}: ${response}")
            println()
        }

        // Print handoff history
        println("===== HANDOFF HISTORY =====")
        handoffManager.getHandoffs(conversationId)?.forEach { handoff ->
            println("${handoff.timestamp}: ${handoff.fromAgentId} → ${handoff.toAgentId} (${handoff.reason})")
        }
    }
})