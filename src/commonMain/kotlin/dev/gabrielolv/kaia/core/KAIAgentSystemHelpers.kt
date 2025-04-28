package dev.gabrielolv.kaia.core

import dev.gabrielolv.kaia.core.agents.*


fun KAIAgentSystemBuilder.llm(block: (LLMAgentBuilder.() -> Unit)) {
    this.addAgent(Agent.llm(block))
}

fun KAIAgentSystemBuilder.database(block: (DatabaseAgentBuilder.() -> Unit)) {
    this.addAgent(Agent.database(block))
}