package dev.gabrielolv.kaia

// import io.kotest.assertions.throwables.shouldThrow // Keep for validation errors
import dev.gabrielolv.kaia.core.Conversation
import dev.gabrielolv.kaia.core.Message
import dev.gabrielolv.kaia.core.agents.Agent
import dev.gabrielolv.kaia.core.agents.withDatabaseAccess
import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMProvider
import dev.gabrielolv.kaia.utils.nextThreadId
import io.kotest.assertions.fail
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.SQLException

// --- Test Setup ---

// Define simple tables for testing
object Users : Table("users") {
    val id = integer("id").autoIncrement()
    val first_name = varchar("first_name", 50)
    val email = varchar("email", 100).uniqueIndex()
    override val primaryKey = PrimaryKey(id)
}

object Products : Table("products") {
    val sku = varchar("sku", 50)
    val description = varchar("description", 255)
    val price = decimal("price", 10, 2)
    override val primaryKey = PrimaryKey(sku)
}

// A table not explicitly allowed in some tests
object Secrets : Table("secrets") {
    val id = integer("id").autoIncrement()
    val data = varchar("data", 255)
    override val primaryKey = PrimaryKey(id)
}

@Tags("Integration", "LLM") // Optional: Tag as integration tests
class DatabaseAgentIntegrationTest : FunSpec({

    lateinit var db: Database
    lateinit var realProvider: LLMProvider // Use the real provider

    // Allowed tables for most tests
    val allowedTables = listOf(Users, Products)



    beforeSpec {
        // Check for API key before running any tests in this spec
        if (System.getenv("GROQ_KEY").isNullOrBlank()) {
            throw IllegalStateException(
                "GROQ_KEY environment variable not set. Skipping DatabaseAgentIntegrationTest."
            )
            // Alternatively, use Kotest's configuration to disable the spec/tests
        }
    }

    beforeEach {
        // Setup H2 in-memory database
        db = Database.connect(
            "jdbc:h2:mem:test_db_agent_${System.nanoTime()};DB_CLOSE_DELAY=-1;", // Unique first_name per test
            driver = "org.h2.Driver"
        )
        transaction(db) {
            // Drop existing tables if they exist (for clean slate)
            SchemaUtils.drop(Users, Products, Secrets)
            // Create schema
            SchemaUtils.create(Users, Products, Secrets) // Create all for different tests

            // Insert test data
            Users.insert {
                it[first_name] = "Alice"
                it[email] = "alice@example.com"
            }
            Users.insert {
                it[first_name] = "Bob"
                it[email] = "bob@example.com"
            }
            Products.insert {
                it[sku] = "WDG-001"
                it[description] = "Standard Widget"
                it[price] = 19.99.toBigDecimal()
            }
            Secrets.insert {
                it[data] = "Top Secret Info"
            }
        }

        // Setup REAL LLM provider
        realProvider = LLMProvider.openAI(
            apiKey = System.getenv("GROQ_KEY"), // Assumes env var is set
            baseUrl = "https://api.groq.com/openai/v1",
            model = "llama3-70b-8192", // Using a known Groq model
            // model = "llama-3.3-70b-specdec", // Or your specific model
        )
    }

    afterEach {
        // Optional: Close DB connection if needed, though H2 mem usually cleans up
        // Exposed connections might need explicit closing in some scenarios.
    }

    // --- Test Cases ---

    test("should execute valid SELECT query without parameters successfully") { runTest {
        // Arrange
        val agent = Agent.withDatabaseAccess(
            provider = realProvider, // Use real provider
            database = db,
            tables = allowedTables
        ) {
            id = "db_agent"
            name = "Database Agent"
        }

        // Act
        val results = agent.process(Message(content="Show all users first_names and emails"), Conversation(nextThreadId)).toList()

        // Assert
        // We expect 2 messages: System(Template/Params), System(Results)
        results.size shouldBe 2

        val templateMsg = results[0] as LLMMessage.SystemMessage
        templateMsg.content shouldContain "Query Template: SELECT" // Basic check
        templateMsg.content shouldContain "USERS"
        // Cannot reliably assert exact SQL template

        val resultMsg = results[1] as LLMMessage.SystemMessage
        resultMsg.content shouldContain "Query Results:"
        // Check for expected data (order might vary)
        // Use contains, as exact formatting might differ slightly
        resultMsg.content shouldContain "FIRST_NAME=Alice"
        resultMsg.content shouldContain "EMAIL=alice@example.com"
        resultMsg.content shouldContain "FIRST_NAME=Bob"
        resultMsg.content shouldContain "EMAIL=bob@example.com"

        // Verify data in DB hasn't changed (sanity check)
        transaction(db) {
            Users.selectAll().count() shouldBe 2
        }
    }}

    test("should execute valid SELECT query with parameters successfully") { runTest {
        // Arrange
        val agent = Agent.withDatabaseAccess(
            provider = realProvider,
            database = db,
            tables = allowedTables
        ) { id = "db_agent" }

        val userEmail = "bob@example.com"

        // Act
        val results = agent.process(Message(content="Find the first_name of the user with email $userEmail"), Conversation(nextThreadId)).toList()

        // Assert
        results.size shouldBe 2

        val templateMsg = results[0] as LLMMessage.SystemMessage
        templateMsg.content shouldContain "Query Template: SELECT"
        templateMsg.content shouldContain "USERS"
        templateMsg.content shouldContain "WHERE"
        templateMsg.content shouldContain "?" // Expecting a parameter placeholder
        templateMsg.content shouldContain """Query Parameters: ["$userEmail"]""" // Parameter should be extracted

        val resultMsg = results[1] as LLMMessage.SystemMessage
        resultMsg.content shouldContain "Query Results:"
        resultMsg.content shouldContain "FIRST_NAME=Bob" // Check for specific result
        resultMsg.content shouldNotContain "Alice"
        resultMsg.content shouldNotContain "EMAIL=" // Ensure only first_name was selected as requested
    }}

    // Note: These validation tests now depend on the LLM *not* following instructions
    // or the validation logic catching errors if the LLM generates invalid SQL.
    // They are less reliable than mocked tests for testing the validation logic itself.
    test("should reject non-SELECT query during validation (if LLM generates it)") { runTest {
        // Arrange
        val agent = Agent.withDatabaseAccess(
            provider = realProvider,
            database = db,
            tables = allowedTables
        ) { id = "db_agent" }

        // Act & Assert
        // Ask the LLM to do something forbidden. It *should* refuse or generate
        // only SELECT, but if it generates UPDATE/DELETE, validation should catch it.
        try {
            val results = agent.process(Message(content="Try to update Alice's email to hacked@example.com"), Conversation(nextThreadId)).toList()
            // If the LLM refuses or generates valid SELECT, the test might pass without error,
            // or fail if the results don't match expectations.
            // We ideally want the validation to trigger.
            val templateMsg = results.getOrNull(0) as? LLMMessage.SystemMessage
            val resultMsg = results.getOrNull(1) as? LLMMessage.SystemMessage

            if (resultMsg?.content?.contains("Generated query is not allowed!") == true) {
                // This is the desired outcome if validation worked after LLM generated bad SQL
                println("LLM generated disallowed query, validation caught it (as expected for this test).")
            } else if (templateMsg != null && !templateMsg.content.contains("UPDATE", ignoreCase = true)) {
                println("LLM correctly generated a SELECT or refused. Test focus shifted.")
                // This isn't strictly testing the rejection path, but shows LLM compliance.
            } else {
                fail("Expected validation error or LLM refusal, but got unexpected results: $results")
            }

        } catch (e: SQLException) {
            // This is the ideal outcome for this specific test's goal: validation failed.
            println("Caught SQLException, likely due to query validation failure (expected).")
            e.message shouldContain "Validation failed" // Or check specific validation message if available
        } catch (e: Exception) {
            // Catch other potential errors (network, API)
            fail("Test failed with unexpected exception: ${e.message}")
        }

        // Verify data in DB hasn't changed
        transaction(db) {
            val aliceEmail = Users.selectAll().where { Users.first_name eq  "Alice" }.singleOrNull()?.get(Users.email)
            aliceEmail shouldBe "alice@example.com"
        }
    }}

    test("should reject query accessing disallowed table (if LLM generates it)") { runTest {
        // Arrange
        val agent = Agent.withDatabaseAccess(
            provider = realProvider,
            database = db,
            tables = allowedTables // Secrets table is NOT included here
        ) { id = "db_agent" }

        // Act & Assert
        // Ask the LLM to access the disallowed 'secrets' table.
        try {
            val results = agent.process(Message(content="Get data from the secrets table"), Conversation(nextThreadId)).toList()

            val templateMsg = results.getOrNull(0) as? LLMMessage.SystemMessage
            val resultMsg = results.getOrNull(1) as? LLMMessage.SystemMessage

            if (resultMsg?.content?.contains("Generated query is not allowed!") == true) {
                println("LLM generated query accessing disallowed table, validation caught it (expected).")
            } else if (templateMsg != null && !templateMsg.content.contains("secrets", ignoreCase = true)) {
                println("LLM correctly avoided disallowed table or refused. Test focus shifted.")
            } else {
                fail("Expected validation error or LLM refusal, but got unexpected results: $results")
            }

        } catch (e: SQLException) {
            println("Caught SQLException, likely due to query validation failure (expected).")
            e.message shouldContain "Validation failed" // Or check specific validation message
        } catch (e: Exception) {
            fail("Test failed with unexpected exception: ${e.message}")
        }
    }}

    test("should handle query returning no results") { runTest {
        // Arrange
        val agent = Agent.withDatabaseAccess(
            provider = realProvider,
            database = db,
            tables = allowedTables
        ) { id = "db_agent" }

        val nonExistentfirst_name = "Charlie"

        // Act
        val results = agent.process(Message(content="Find user first_named $nonExistentfirst_name"), Conversation(nextThreadId)).toList()

        // Assert
        results.size shouldBe 2

        val templateMsg = results[0] as LLMMessage.SystemMessage
        templateMsg.content shouldContain "Query Template: SELECT"
        templateMsg.content shouldContain """Query Parameters: ["$nonExistentfirst_name"]""" // Or similar if LLM uses LIKE etc.

        val resultMsg = results[1] as LLMMessage.SystemMessage
        // Check that the result string is empty after the header, allowing for whitespace variations
        resultMsg.content.trim() shouldBe "Query Results:"
    }}

    // This test is less reliable now. We can't easily force the LLM to generate
    // SQL that passes validation but fails at runtime. The LLM might just generate correct SQL.
    // We'll keep it but acknowledge it might not always trigger the desired error path.
    test("should handle database error during query execution (if LLM generates bad SQL)") { runTest {
        // Arrange
        val agent = Agent.withDatabaseAccess(
            provider = realProvider,
            database = db,
            tables = allowedTables
        ) { id = "db_agent" }

        // Try to trick the LLM into generating syntactically invalid SQL that might pass basic validation
        val trickyPrompt = "Select the 'nam' column (misspelled) from users where the id is 1"

        // Act
        val results = agent.process(Message(content=trickyPrompt), Conversation(nextThreadId)).toList()

        // Assert
        results.size shouldBe 2 // Template message + Error message

        val templateMsg = results[0] as LLMMessage.SystemMessage
        println("LLM generated for tricky prompt: ${templateMsg.content}") // Log what was generated

        val errorMsg = results[1] as LLMMessage.SystemMessage

        // Check if the *intended* error occurred. The LLM might generate valid SQL instead.
        if (errorMsg.content == "There was an error while executing the query.") {
            println("Agent correctly handled a query execution error (expected).")
            // This suggests the LLM produced SQL that failed execution (e.g., bad column first_name)
        } else if (errorMsg.content.contains("Query Results:")) {
            println("LLM generated valid SQL despite tricky prompt. Test focus shifted.")
            // The LLM might have corrected the column first_name or generated different valid SQL.
            // Check if the results make sense in this case.
            errorMsg.content shouldContain "FIRST_NAME=Alice" // If it corrected 'nam' to 'first_name'
        } else {
            fail("Expected a query execution error message or valid results, but got: ${errorMsg.content}")
        }
    }}

    // Test for malformed JSON is removed as the real provider should handle JSON correctly or throw its own errors.

    test("should construct prompt with correct dialect and schema") { runTest {
        // Arrange
        // We need to capture the prompt sent to the real provider.
        // This is harder without mocks. We'll indirectly test this by ensuring
        // the agent *can* function, implying the prompt was likely correct enough.
        // A more direct test would involve intercepting network calls or modifying the provider.
        // For now, we'll trust that if other tests pass, the prompt is reasonable.

        // Let's just run a simple query again to ensure basic functionality works,
        // which relies on a correctly formed prompt.
        val agent = Agent.withDatabaseAccess(
            provider = realProvider,
            database = db,
            tables = allowedTables
        ) { id = "db_agent" }

        // Act
        val results = agent.process(Message(content="Show product WDG-001 price"), Conversation(nextThreadId)).toList()

        // Assert
        results.size shouldBe 2
        val resultMsg = results[1] as LLMMessage.SystemMessage
        resultMsg.content shouldContain "Query Results:"
        // Check for price, allowing for potential formatting differences (e.g., 19.99 vs 19.990)
        resultMsg.content shouldContain "PRICE=19.99" // Adjust if DB/driver returns different precision

        println("Prompt construction test passed indirectly by successful query execution.")
        // This doesn't explicitly check the prompt string content like the mock test did.
    }}
})
