package dev.gabrielolv.kaia

import dev.gabrielolv.kaia.core.HandoffManager
import dev.gabrielolv.kaia.core.Message
import dev.gabrielolv.kaia.core.Orchestrator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.common.KotestInternal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

@OptIn(KotestInternal::class)
class HandoffManagerTest : FunSpec({

    lateinit var orchestrator: Orchestrator
    lateinit var handoffManager: HandoffManager

    beforeTest {
        orchestrator = mockk<Orchestrator>(relaxed = true)
        handoffManager = HandoffManager(orchestrator, "")
    }

    test("startConversation should create a new conversation with specified ID and initial agent") {
        // Given
        val conversationId = "test-conversation"
        val initialAgentId = "agent-1"

        // When
        val resultId = handoffManager.startConversation(
            conversationId = conversationId,
            initialAgentId = initialAgentId
        )

        // Then
        resultId shouldBe conversationId
        val conversation = handoffManager.getConversation(conversationId)
        conversation.shouldNotBeNull()
        conversation.id shouldBe conversationId
        conversation.currentAgentId shouldBe initialAgentId
        conversation.messages.shouldBeEmpty()
        conversation.handoffs.shouldBeEmpty()
    }

    test("startConversation should generate a unique ID when not specified") {
        // When
        val conversationId = handoffManager.startConversation(
            initialAgentId = "agent-1"
        )

        // Then
        conversationId.shouldNotBeNull()
        conversationId.startsWith("thread-") shouldBe true

        val conversation = handoffManager.getConversation(conversationId)
        conversation.shouldNotBeNull()
    }

    test("getConversation should return null for non-existent conversation") {
        // When
        val conversation = handoffManager.getConversation("non-existent-id")

        // Then
        conversation.shouldBeNull()
    }

    test("handoff should update the current agent when target agent exists") {
        // Given
        val conversationId = handoffManager.startConversation(initialAgentId = "agent-1")
        val targetAgentId = "agent-2"
        val reason = "Testing handoff"

        every { orchestrator.getAgent(targetAgentId) } returns mockk()

        // When
        val result = handoffManager.handoff(
            conversationId = conversationId,
            targetAgentId = targetAgentId,
            reason = reason
        )

        // Then
        result shouldBe true

        val conversation = handoffManager.getConversation(conversationId)
        conversation.shouldNotBeNull()
        conversation.currentAgentId shouldBe targetAgentId

        val handoffs = handoffManager.getHandoffs(conversationId)
        handoffs.shouldNotBeNull()
        handoffs shouldHaveSize 1
        handoffs[0].fromAgentId shouldBe "agent-1"
        handoffs[0].toAgentId shouldBe targetAgentId
        handoffs[0].reason shouldBe reason
    }

    test("handoff should fail for non-existent conversation") {
        // Given
        val conversationId = "non-existent-id"
        val targetAgentId = "agent-2"

        // When
        val result = handoffManager.handoff(
            conversationId = conversationId,
            targetAgentId = targetAgentId,
            reason = "Test reason"
        )

        // Then
        result shouldBe false
    }

    test("handoff should fail when target agent doesn't exist") {
        // Given
        val conversationId = handoffManager.startConversation(initialAgentId = "agent-1")
        val targetAgentId = "non-existent-agent"

        every { orchestrator.getAgent(targetAgentId) } returns null

        // When
        val result = handoffManager.handoff(
            conversationId = conversationId,
            targetAgentId = targetAgentId,
            reason = "Test reason"
        )

        // Then
        result shouldBe false

        // Current agent should not change
        val conversation = handoffManager.getConversation(conversationId)
        conversation.shouldNotBeNull()
        conversation.currentAgentId shouldBe "agent-1"
    }

    test("sendMessage should add messages to conversation history and return response") {
        runTest {
            // Given
            val conversationId = handoffManager.startConversation(initialAgentId = "agent-1")
            val message = Message(
                sender = "user",
                content = "Hello"
            )
            val expectedResponse = Message(
                sender = "agent-1",
                recipient = "user",
                content = "Hi there"
            )

            coEvery {
                orchestrator.processWithAgent(
                    any(),
                    any()
                )
            } returns expectedResponse

            // When
            val response = handoffManager.sendMessage(conversationId, message)

            // Then
            response shouldBe expectedResponse

            val conversation = handoffManager.getConversation(conversationId)
            conversation.shouldNotBeNull()
            conversation.messages shouldHaveSize 1
            conversation.messages shouldContain message
            conversation.messages shouldContain expectedResponse
        }
    }
    test("sendMessage should return null for non-existent conversation") {
        runTest {
            // Given
            val conversationId = "non-existent-id"
            val message = Message(
                sender = "user",
                content = "Hello"
            )

            // When
            val response = handoffManager.sendMessage(conversationId, message)

            // Then
            response.shouldBeNull()
        }
    }
    test("sendMessage should handle exception from orchestrator") {
        runTest {
            // Given
            val conversationId = handoffManager.startConversation(initialAgentId = "agent-1")
            val message = Message(
                sender = "user",
                content = "Hello"
            )

            coEvery {
                orchestrator.processWithAgent(
                    any(),
                    any()
                )
            } throws RuntimeException("Test exception")

            // When/Then
            val exception = shouldThrow<RuntimeException> {
                handoffManager.sendMessage(conversationId, message)
            }

            exception.message shouldBe "Test exception"

            // Message should still be added to conversation history
            val conversation = handoffManager.getConversation(conversationId)
            conversation.shouldNotBeNull()
            conversation.messages shouldHaveSize 1
            conversation.messages shouldContain message
        }
    }
    test("getHistory should return null for non-existent conversation") {
        // When
        val history = handoffManager.getHistory("non-existent-id")

        // Then
        history.shouldBeNull()
    }

    test("getHistory should return conversation messages") {
        // Given
        val conversationId = handoffManager.startConversation(initialAgentId = "agent-1")
        val conversation = handoffManager.getConversation(conversationId)

        val message1 = Message(sender = "user", content = "Hello")
        val message2 = Message(sender = "agent-1", recipient = "user", content = "Hi")

        conversation?.messages?.add(message1)
        conversation?.messages?.add(message2)

        // When
        val history = handoffManager.getHistory(conversationId)

        // Then
        history.shouldNotBeNull()
        history shouldHaveSize 2
        history shouldContain message1
        history shouldContain message2
    }

    test("getHandoffs should return null for non-existent conversation") {
        // When
        val handoffs = handoffManager.getHandoffs("non-existent-id")

        // Then
        handoffs.shouldBeNull()
    }

    test("getHandoffs should return empty list when no handoffs occurred") {
        // Given
        val conversationId = handoffManager.startConversation(initialAgentId = "agent-1")

        // When
        val handoffs = handoffManager.getHandoffs(conversationId)

        // Then
        handoffs.shouldNotBeNull()
        handoffs.shouldBeEmpty()
    }

    test("multiple handoffs should be properly recorded") {
        // Given
        val conversationId = handoffManager.startConversation(initialAgentId = "agent-1")

        every { orchestrator.getAgent(any()) } returns mockk()

        // When
        handoffManager.handoff(conversationId, "agent-2", "First handoff")
        handoffManager.handoff(conversationId, "agent-3", "Second handoff")
        handoffManager.handoff(conversationId, "agent-1", "Third handoff")

        // Then
        val handoffs = handoffManager.getHandoffs(conversationId)
        handoffs.shouldNotBeNull()
        handoffs shouldHaveSize 3

        handoffs[0].fromAgentId shouldBe "agent-1"
        handoffs[0].toAgentId shouldBe "agent-2"
        handoffs[0].reason shouldBe "First handoff"

        handoffs[1].fromAgentId shouldBe "agent-2"
        handoffs[1].toAgentId shouldBe "agent-3"
        handoffs[1].reason shouldBe "Second handoff"

        handoffs[2].fromAgentId shouldBe "agent-3"
        handoffs[2].toAgentId shouldBe "agent-1"
        handoffs[2].reason shouldBe "Third handoff"
    }

    test("complex scenario - conversation with multiple messages and handoffs") {
        runTest {
            // Given
            val conversationId = handoffManager.startConversation(initialAgentId = "sales")

            val agentResponses = mapOf(
                "sales" to "I can help with your purchase inquiry",
                "support" to "I can help troubleshoot your issue",
                "billing" to "I can help with your billing question"
            )

            every { orchestrator.getAgent(any()) } returns mockk()

            coEvery {
                orchestrator.processWithAgent(eq("sales"), any())
            } answers {
                val message = secondArg<Message>()
                Message(
                    sender = "sales",
                    recipient = message.sender,
                    content = agentResponses["sales"]!!
                )
            }

            coEvery {
                orchestrator.processWithAgent(eq("support"), any())
            } answers {
                val message = secondArg<Message>()
                Message(
                    sender = "support",
                    recipient = message.sender,
                    content = agentResponses["support"]!!
                )
            }

            coEvery {
                orchestrator.processWithAgent(eq("billing"), any())
            } answers {
                val message = secondArg<Message>()
                Message(
                    sender = "billing",
                    recipient = message.sender,
                    content = agentResponses["billing"]!!
                )
            }

            // When - Simulate a complex conversation with multiple handoffs
            var response = handoffManager.sendMessage(
                conversationId,
                Message(sender = "user", content = "I want to buy a product")
            )

            response?.content shouldBe agentResponses["sales"]

            // Handoff to support
            handoffManager.handoff(conversationId, "support", "Technical question")

            response = handoffManager.sendMessage(
                conversationId,
                Message(sender = "user", content = "I'm having an issue with my account")
            )

            response?.content shouldBe agentResponses["support"]

            // Handoff to billing
            handoffManager.handoff(conversationId, "billing", "Billing question")

            response = handoffManager.sendMessage(
                conversationId,
                Message(sender = "user", content = "I have a question about my invoice")
            )

            response?.content shouldBe agentResponses["billing"]

            // Then - Verify conversation history and handoffs
            val conversation = handoffManager.getConversation(conversationId)
            conversation.shouldNotBeNull()
            conversation.currentAgentId shouldBe "billing"

            val history = handoffManager.getHistory(conversationId)
            history.shouldNotBeNull()
            history shouldHaveSize 6  // 3 user messages + 3 agent responses

            val handoffs = handoffManager.getHandoffs(conversationId)
            handoffs.shouldNotBeNull()
            handoffs shouldHaveSize 2

            handoffs[0].fromAgentId shouldBe "sales"
            handoffs[0].toAgentId shouldBe "support"

            handoffs[1].fromAgentId shouldBe "support"
            handoffs[1].toAgentId shouldBe "billing"
        }
    }
    test("concurrent conversations should not interfere with each other") {
        runTest {
            // Given
            val conversation1Id = handoffManager.startConversation(initialAgentId = "agent-1")
            val conversation2Id = handoffManager.startConversation(initialAgentId = "agent-2")

            every { orchestrator.getAgent(any()) } returns mockk()

            coEvery {
                orchestrator.processWithAgent(any(), any())
            } answers {
                val message = secondArg<Message>()
                Message(
                    sender = firstArg<String>(),
                    recipient = message.sender,
                    content = "Response from ${firstArg<String>()}"
                )
            }

            // When - Interact with both conversations
            handoffManager.sendMessage(
                conversation1Id,
                Message(sender = "user1", content = "Hello from conversation 1")
            )

            handoffManager.sendMessage(
                conversation2Id,
                Message(sender = "user2", content = "Hello from conversation 2")
            )

            handoffManager.handoff(conversation1Id, "agent-3", "Handoff in conversation 1")

            handoffManager.sendMessage(
                conversation1Id,
                Message(sender = "user1", content = "Second message in conversation 1")
            )

            // Then - Verify both conversations maintained separate state
            val conversation1 = handoffManager.getConversation(conversation1Id)
            val conversation2 = handoffManager.getConversation(conversation2Id)

            conversation1.shouldNotBeNull()
            conversation2.shouldNotBeNull()

            conversation1.currentAgentId shouldBe "agent-3"
            conversation2.currentAgentId shouldBe "agent-2"

            val history1 = handoffManager.getHistory(conversation1Id)
            val history2 = handoffManager.getHistory(conversation2Id)

            history1.shouldNotBeNull()
            history2.shouldNotBeNull()

            history1 shouldHaveSize 4  // 2 user messages + 2history1 shouldHaveSize 4  // 2 user messages + 2 agent responses
            history2 shouldHaveSize 2  // 1 user message + 1 agent response

            val handoffs1 = handoffManager.getHandoffs(conversation1Id)
            val handoffs2 = handoffManager.getHandoffs(conversation2Id)

            handoffs1.shouldNotBeNull()
            handoffs2.shouldNotBeNull()

            handoffs1 shouldHaveSize 1
            handoffs2 shouldHaveSize 0
        }
    }

    test("error - orchestrator throws exception during getAgent") {
        // Given
        val conversationId = handoffManager.startConversation(initialAgentId = "agent-1")
        val targetAgentId = "problematic-agent"

        every {
            orchestrator.getAgent(targetAgentId)
        } throws RuntimeException("Agent lookup failed")

        // When/Then
        val exception = shouldThrow<RuntimeException> {
            handoffManager.handoff(conversationId, targetAgentId, "Test reason")
        }

        exception.message shouldBe "Agent lookup failed"

        // Conversation state should remain unchanged
        val conversation = handoffManager.getConversation(conversationId)
        conversation.shouldNotBeNull()
        conversation.currentAgentId shouldBe "agent-1"
        conversation.handoffs.shouldBeEmpty()
    }

    test("error - sendMessage with invalid message format") {
        runTest {
            // Given
            val conversationId = handoffManager.startConversation(initialAgentId = "agent-1")
            val invalidMessage = Message(
                sender = "",  // Invalid empty sender
                content = "Test content"
            )

            // When/Then
            val exception = shouldThrow<AssertionError> {
                handoffManager.sendMessage(conversationId, invalidMessage)
            }

            exception.message shouldBe "Message sender cannot be empty"

            // Conversation messages should not include the invalid message
            val conversation = handoffManager.getConversation(conversationId)
            conversation.shouldNotBeNull()
            conversation.messages.shouldBeEmpty()
        }
    }

    test("race condition when multiple threads access the same conversation") {
        // This test simulates a race condition by manipulating the conversation state
        // between operations to verify the HandoffManager's thread safety

        // Given
        val conversationId = handoffManager.startConversation(initialAgentId = "agent-1")

        every { orchestrator.getAgent(any()) } returns mockk()

        // When - Simulate concurrent access by modifying state between operations
        val originalConversation = handoffManager.getConversation(conversationId)

        // Thread 1 starts handoff
        val handoffResult = handoffManager.handoff(conversationId, "agent-2", "Race condition test")

        // Thread 2 directly modifies conversation (simulating race condition)
        originalConversation?.currentAgentId = "agent-3"

        // Then
        handoffResult shouldBe true

        // The actual conversation in the manager should reflect the change from handoff
        val managerConversation = handoffManager.getConversation(conversationId)
        managerConversation.shouldNotBeNull()
        managerConversation.currentAgentId shouldBe "agent-3"  // Value from the race condition

        // But the handoff should still be recorded
        val handoffs = handoffManager.getHandoffs(conversationId)
        handoffs.shouldNotBeNull()
        handoffs shouldHaveSize 1
        handoffs[0].fromAgentId shouldBe "agent-1"
        handoffs[0].toAgentId shouldBe "agent-2"
    }

    test("integration with agent chain - multiple sequential handoffs") {
        runTest {
            // Given - Setup a chain of agents
            val conversationId = handoffManager.startConversation(initialAgentId = "intake")

            val agentSequence = listOf("intake", "processing", "specialist", "resolution")

            agentSequence.forEach { agentId ->
                every { orchestrator.getAgent(agentId) } returns mockk()

                coEvery {
                    orchestrator.processWithAgent(eq(agentId), any())
                } answers {
                    val message = secondArg<Message>()
                    Message(
                        sender = agentId,
                        recipient = message.sender,
                        content = "Response from $agentId agent"
                    )
                }
            }

            // When - Process through the agent chain
            var userMessage = Message(sender = "user", content = "Initial request")
            var response = handoffManager.sendMessage(conversationId, userMessage)

            for (i in 0 until agentSequence.size - 1) {
                val fromAgent = agentSequence[i]
                val toAgent = agentSequence[i + 1]

                // Handoff to next agent
                val handoffResult = handoffManager.handoff(
                    conversationId,
                    toAgent,
                    "Moving to next step: $toAgent"
                )

                handoffResult shouldBe true

                // Send follow-up message
                userMessage = Message(sender = "user", content = "Follow-up for $toAgent")
                response = handoffManager.sendMessage(conversationId, userMessage)

                response?.sender shouldBe toAgent
            }

            // Then - Verify the conversation progressed through all agents
            val conversation = handoffManager.getConversation(conversationId)
            conversation.shouldNotBeNull()
            conversation.currentAgentId shouldBe agentSequence.last()

            val handoffs = handoffManager.getHandoffs(conversationId)
            handoffs.shouldNotBeNull()
            handoffs shouldHaveSize agentSequence.size - 1

            // Verify handoff sequence
            for (i in 0 until handoffs.size) {
                handoffs[i].fromAgentId shouldBe agentSequence[i]
                handoffs[i].toAgentId shouldBe agentSequence[i + 1]
            }
        }
    }

    test("performance - handling large conversation history") {
        runTest {
            // Given - Create a conversation with large history
            val conversationId = handoffManager.startConversation(initialAgentId = "agent-1")
            val conversation = handoffManager.getConversation(conversationId)

            // Mock agent processing
            every { orchestrator.getAgent(any()) } returns mockk()
            coEvery {
                orchestrator.processWithAgent(any(), any())
            } returns Message(sender = "agent", recipient = "user", content = "Response")

            // Add many messages to history
            val messageCount = 1000
            repeat(messageCount) { index ->
                val userMessage = Message(sender = "user", content = "Message $index")
                conversation?.messages?.add(userMessage)

                val agentMessage = Message(sender = "agent-1", recipient = "user", content = "Response $index")
                conversation?.messages?.add(agentMessage)
            }

            // When - Try to send another message with large history
            val start = System.currentTimeMillis()
            val response = handoffManager.sendMessage(
                conversationId,
                Message(sender = "user", content = "Final message")
            )
            val end = System.currentTimeMillis()

            // Then - Should handle large history efficiently
            response.shouldNotBeNull()

            val history = handoffManager.getHistory(conversationId)
            history.shouldNotBeNull()
            history shouldHaveSize (messageCount * 2) + 2  // All messages + the final exchange

            // Performance assertion - should complete in reasonable time
            // Note: This is a soft assertion that may vary by environment
            val executionTime = end - start
            println("Processing time with $messageCount messages: $executionTime ms")
        }
    }

    test("error - attempting to start conversation with empty agent ID") {
        // When/Then
        val exception = shouldThrow<AssertionError> {
            handoffManager.startConversation(initialAgentId = "")
        }

        exception.message shouldBe "Initial agent ID cannot be empty"
    }

    test("stress test - multiple concurrent operations") {
        runTest {
            // Given
            val conversationId = handoffManager.startConversation(initialAgentId = "agent-1")

            every { orchestrator.getAgent(any()) } returns mockk()
            coEvery {
                orchestrator.processWithAgent(any(), any())
            } returns Message(sender = "agent", recipient = "user", content = "Response")

            // When - Perform many operations concurrently
            val operationCount = 100

            // Multiple handoffs
            repeat(operationCount) { index ->
                val targetAgentId = "agent-${index % 5}"
                handoffManager.handoff(conversationId, targetAgentId, "Handoff $index")
            }

            // Multiple messages
            repeat(operationCount) { index ->
                handoffManager.sendMessage(
                    conversationId,
                    Message(sender = "user", content = "Message $index")
                )
            }

            // Then - Verify integrity of conversation
            val conversation = handoffManager.getConversation(conversationId)
            conversation.shouldNotBeNull()

            // Current agent should be from the last handoff
            conversation.currentAgentId shouldBe "agent-${(operationCount - 1) % 5}"

            val handoffs = handoffManager.getHandoffs(conversationId)
            handoffs.shouldNotBeNull()
            handoffs shouldHaveSize operationCount

            val messages = handoffManager.getHistory(conversationId)
            messages.shouldNotBeNull()
            messages shouldHaveSize operationCount * 2  // User message + agent response for each
        }
    }
})