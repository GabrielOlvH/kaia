package dev.gabrielolv.kaia.core.agents

import dev.gabrielolv.kaia.core.Conversation
import dev.gabrielolv.kaia.core.Message
import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider
import dev.gabrielolv.kaia.utils.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table

@OptIn(ExperimentalSerializationApi::class)
private val json = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
}

interface DatabaseQueryListener {
    suspend fun onQueryExecuted(
        sqlTemplate: String,
        parameters: List<SqlParameter>,
        results: List<Map<String, Any?>>?,
        error: Exception?
    )
}

class DatabaseAgentBuilder : AgentBuilder() {
    var provider: LLMProvider? = null
    var database: Database? = null
    var tables: List<Table> = emptyList()
    var predefinedQueries: Map<Int, PreDefinedQuery> = emptyMap()
    var mode: DatabaseAgentMode = DatabaseAgentMode.HYBRID
    var listeners: List<DatabaseQueryListener> = emptyList()
}

fun DatabaseAgentBuilder.buildProcessor(): (Message, Conversation) -> Flow<LLMMessage> {
    requireNotNull(provider) { "LLMProvider must be set" }
    requireNotNull(database) { "Database must be set" }
    require(tables.isNotEmpty()) { "Tables list cannot be empty" }

    val provider = provider!!
    val database = database!!
    val prompt = buildPrompt(this)

    return { _, conversation ->
        flow {
            val options = LLMOptions(
                responseFormat = "json_object",
                systemPrompt = prompt,
                temperature = 0.1
            )
            val response = provider.generate(conversation.messages, options).toList().last { it is LLMMessage.AssistantMessage }
            val responseContent = (response as LLMMessage.AssistantMessage).content

            try {
                when (mode) {
                    DatabaseAgentMode.FREE -> {
                        val generatedSql = json.decodeFromString<GeneratedSql>(responseContent)
                        emit(LLMMessage.SystemMessage("Query Template: ${generatedSql.sqlTemplate}\nQuery Parameters: ${generatedSql.parameters}"))
                        emit(execute(database, generatedSql, listeners))
                    }
                    DatabaseAgentMode.PREDEFINED_ONLY -> {
                        val selection = json.decodeFromString<PreDefinedQuerySelection>(responseContent)
                        val predefinedQuery = predefinedQueries[selection.queryId]

                        if (predefinedQuery != null) {
                            emit(LLMMessage.SystemMessage("Using predefined query #${selection.queryId}: ${predefinedQuery.description}"))
                            val generatedSql = GeneratedSql(
                                sqlTemplate = predefinedQuery.sqlTemplate,
                                parameters = selection.parameters
                            )
                            emit(execute(database, generatedSql, listeners))
                        } else {
                            emit(LLMMessage.SystemMessage("Error: Predefined query #${selection.queryId} does not exist"))
                        }
                    }
                    DatabaseAgentMode.HYBRID -> {
                        if (responseContent.contains("query_id") && predefinedQueries.isNotEmpty()) {
                            try {
                                val selection = json.decodeFromString<PreDefinedQuerySelection>(responseContent)
                                val predefinedQuery = predefinedQueries[selection.queryId]

                                if (predefinedQuery != null) {
                                    emit(LLMMessage.SystemMessage("Using predefined query #${selection.queryId}: ${predefinedQuery.description}"))
                                    val generatedSql = GeneratedSql(
                                        sqlTemplate = predefinedQuery.sqlTemplate,
                                        parameters = selection.parameters
                                    )
                                    emit(execute(database, generatedSql, listeners))
                                    return@flow
                                } else {
                                    emit(LLMMessage.SystemMessage("Error: Predefined query #${selection.queryId} does not exist. Falling back to custom SQL generation."))
                                }
                            } catch (e: Exception) {
                                // Failed to parse as predefined query, continue to try custom SQL
                            }
                        }

                        val generatedSql = json.decodeFromString<GeneratedSql>(responseContent)
                        emit(LLMMessage.SystemMessage("Query Template: ${generatedSql.sqlTemplate}\nQuery Parameters: ${generatedSql.parameters}"))
                        emit(execute(database, generatedSql, listeners))
                    }
                }
            } catch (e: Exception) {
                emit(LLMMessage.SystemMessage("Error parsing response: ${e.message}\nResponse: $responseContent"))
            }
        }
    }
}

fun Agent.Companion.withDatabaseAccess(block: DatabaseAgentBuilder.() -> Unit): Agent {
    val builder = DatabaseAgentBuilder().apply(block)
    builder.processor = builder.buildProcessor()
    return builder.build()
}