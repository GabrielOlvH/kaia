package dev.gabrielolv.kaia.core

import app.cash.sqldelight.db.SqlDriver
import dev.gabrielolv.kaia.core.agents.Agent
import dev.gabrielolv.kaia.core.agents.DatabaseAgentBuilder
import dev.gabrielolv.kaia.core.agents.llm
import dev.gabrielolv.kaia.core.agents.withDatabaseAccess
import dev.gabrielolv.kaia.llm.LLMProvider

// --- Helper Extensions for KAIAgentSystemBuilder ---

/**
 * Adds a basic LLM agent to the system with common configurations.
 *
 * ASSUMPTION: Requires an `Agent.Companion.withLLM { ... }` builder to be defined.
 *
 * @param id The unique ID for this agent.
 * @param name A descriptive name for the agent.
 * @param description A description of the agent's capabilities (used by the Director).
 * @param provider The LLMProvider instance this agent will use.
 * @param systemPrompt An optional system prompt to guide the LLM's behavior.
 * @param memory An optional ChatMemory instance for conversation history.
 */
fun KAIAgentSystemBuilder.withLLMAgent(
    id: String,
    name: String,
    description: String,
    provider: LLMProvider,
    systemPrompt: String? = null,
) {
    val agent = Agent.llm {
        this.id = id
        this.name = name
        this.description = description
        this.provider = provider
        if (systemPrompt != null) {
            this.systemPrompt = systemPrompt
        }
    }
    this.addAgent(agent)
}

/**
 * Adds a DatabaseAgent to the system with required configurations.
 *
 * @param id The unique ID for this agent.
 * @param name A descriptive name for the agent.
 * @param description A description of the agent's capabilities (used by the Director).
 * @param provider The LLMProvider instance this agent will use for query generation/selection.
 * @param driver The SqlDriver for connecting to the database.
 * @param dialect The SQL dialect (e.g., "sqlite", "postgresql").
 * @param schemaDescription A detailed description of the database schema.
 * @param block An optional lambda for further DatabaseAgent-specific configuration (predefined queries, listener, etc.).
 */
fun KAIAgentSystemBuilder.withDatabaseAccess(
    id: String,
    name: String,
    description: String,
    provider: LLMProvider,
    driver: SqlDriver,
    dialect: String,
    schemaDescription: String,
    block: (DatabaseAgentBuilder.() -> Unit)? = null
) {
    val agent = Agent.withDatabaseAccess {
        this.id = id
        this.name = name
        this.description = description
        this.provider = provider
        this.driver = driver
        this.dialect = dialect
        this.schemaDescription = schemaDescription
        block?.invoke(this)
    }
    this.addAgent(agent) // Use the builder's internal addAgent
}

// Add more helpers for other common agent types as needed
