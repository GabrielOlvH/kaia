package dev.gabrielolv.kaia.core.agents

import dev.gabrielolv.kaia.core.KAIAgentSystemBuilder


fun KAIAgentSystemBuilder.buildLLMAgent(block: (LLMAgentBuilder.() -> Unit)): Agent {
    return this.addAgent(Agent.llm(block))
}

fun KAIAgentSystemBuilder.buildDatabaseAgent(block: (DatabaseAgentBuilder.() -> Unit)): Agent {
    return this.addAgent(Agent.database(block))
}

fun KAIAgentSystemBuilder.buildDirectorAgent(block: (DirectorAgentBuilder.() -> Unit)): Agent  {
    return this.addAgent(Agent.director(block))
}