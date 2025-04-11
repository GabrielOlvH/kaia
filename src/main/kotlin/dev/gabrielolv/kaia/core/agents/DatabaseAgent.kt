package dev.gabrielolv.kaia.core.agents

import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider
import dev.gabrielolv.kaia.utils.*
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

fun Agent.Companion.withDatabaseAccess(
    provider: LLMProvider,
    database: Database,
    tables: List<Table>,
    predefinedQueries: Map<Int, PreDefinedQuery> = emptyMap(),
    mode: DatabaseAgentMode = DatabaseAgentMode.HYBRID,
    block: AgentBuilder.() -> Unit
): Agent {
    val builder = AgentBuilder().apply(block)

    val dialect = database.dialect.name
    
    val predefinedQueriesText = if (predefinedQueries.isNotEmpty()) {
        """
        **Predefined Queries:**
        ${predefinedQueries.entries.joinToString("\n") { (id, query) ->
            """
            Query #$id: ${query.description}
            Parameters: ${if (query.parameterDescriptions.isEmpty()) "None" else query.parameterDescriptions.joinToString(", ")}
            
            Response format:
            {
              "query_id": $id,
              "parameters": [
                {"value": "your_value", "type": "TYPE"},
                ...
              ]
            }
            
            Valid parameter types: STRING, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP
            """
            .trimIndent()
        }}
        
        **Instructions for Predefined Queries:**
        If a predefined query matches the user's intent, respond with a JSON object containing:
        - `"query_id"`: The number of the predefined query to use
        - `"parameters"`: An array of parameter objects, each containing:
          - `"value"`: The parameter value as a string
          - `"type"`: One of: "STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "TIMESTAMP"
        
        Example: {
          "query_id": 1, 
          "parameters": [
            {"value": "John", "type": "STRING"},
            {"value": "2024-01-01", "type": "DATE"}
          ]
        }
        """
    } else {
        ""
    }
    
    val customQueryInstructions = """
        **Instructions for Custom SQL:**
        Generate a JSON object containing:
        - `"sql_template"`: A string containing the SQL query template. Use standard JDBC placeholders (`?`) for all user-provided values or literals derived from the request.
        - `"parameters"`: A JSON array of objects, each containing:
          - `"value"`: The parameter value as a string
          - `"type"`: One of: "STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "TIMESTAMP"
        
        Example: {
          "sql_template": "SELECT * FROM users WHERE age > ? AND join_date > ?",
          "parameters": [
            {"value": "18", "type": "INTEGER"},
            {"value": "2024-01-01", "type": "DATE"}
          ]
        }
        """
    
    val modeSpecificInstructions = when (mode) {
        DatabaseAgentMode.FREE -> {
            customQueryInstructions
        }
        DatabaseAgentMode.PREDEFINED_ONLY -> {
            if (predefinedQueries.isEmpty()) {
                throw IllegalArgumentException("PREDEFINED_ONLY mode requires at least one predefined query")
            }
            predefinedQueriesText
        }
        DatabaseAgentMode.HYBRID -> {
            if (predefinedQueries.isEmpty()) {
                customQueryInstructions
            } else {
                """
                $predefinedQueriesText
                
                $customQueryInstructions
                
                You can either:
                1. Use a predefined query if it matches the user's intent exactly, or
                2. Generate a custom SQL query, using the predefined queries as examples/reference
                """
            }
        }
    }
    
    val prompt = """
    You are an AI assistant that translates natural language questions into database queries for a $dialect database.
    
    **Database Schema:**
    ```sql
    ${getDdlForTables(tables)}
    ```
    
    $modeSpecificInstructions
    
    Output **only** the raw JSON object, without any surrounding text or markdown formatting.
    """
    
    builder.processor = processor@{ _, conversation ->
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
                        emit(execute(database, generatedSql))
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
                            emit(execute(database, generatedSql))
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
                                    emit(execute(database, generatedSql))
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
                        emit(execute(database, generatedSql))
                    }
                }
            } catch (e: Exception) {
                emit(LLMMessage.SystemMessage("Error parsing response: ${e.message}\nResponse: $responseContent"))
            }
        }
    }

    return builder.build()
}
