package dev.gabrielolv.kaia.core

import dev.gabrielolv.kaia.core.agents.*


fun KAIAgentSystemBuilder.buildLLMAgent(block: (LLMAgentBuilder.() -> Unit)) {
    this.addAgent(Agent.llm(block))
}

fun KAIAgentSystemBuilder.buildDatabaseAgent(block: (DatabaseAgentBuilder.() -> Unit)) {
    this.addAgent(Agent.database(block))
}

fun KAIAgentSystemBuilder.buildDirectorAgent(block: (DirectorAgentBuilder.() -> Unit)) {
    this.addAgent(Agent.director(block))
}