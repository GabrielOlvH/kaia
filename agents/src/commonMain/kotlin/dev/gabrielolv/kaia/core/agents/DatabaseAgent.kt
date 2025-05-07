package dev.gabrielolv.kaia.core.agents

import dev.gabrielolv.kaia.core.Conversation
import dev.gabrielolv.kaia.core.database.SqlExecutor
import dev.gabrielolv.kaia.core.database.SqlResult
import dev.gabrielolv.kaia.core.model.*
import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider
import dev.gabrielolv.kaia.utils.GeneratedSql
import dev.gabrielolv.kaia.utils.PreDefinedQuery
import dev.gabrielolv.kaia.utils.PreDefinedQuerySelection
import dev.gabrielolv.kaia.utils.QueryGenerationRule
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * Convenience function to create a [DatabaseAgent] using the builder pattern.
 */
fun Agent.Companion.database(block: DatabaseAgentBuilder.() -> Unit): Agent {
    val builder = DatabaseAgentBuilder().apply(block)
    builder.processor = builder.buildProcessor()
    return builder.build()
}

@OptIn(ExperimentalSerializationApi::class)
private val json = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Builder for creating and configuring a [DatabaseAgent].
 */
class DatabaseAgentBuilder : AgentBuilder() {
    var provider: LLMProvider? = null
    var dialect: String? = null
    var sqlExecutor: SqlExecutor? = null
    var schemaDescription: String? = null
    var predefinedQueries: Map<Int, PreDefinedQuery> = emptyMap()
    var queryGenerationRule: QueryGenerationRule = QueryGenerationRule.LOOSE

    fun buildProcessor(): (LLMMessage.UserMessage, Conversation) -> Flow<AgentResult> {
        requireNotNull(provider) { "LLMProvider must be set" }
        requireNotNull(dialect) { "Dialect must be set" }
        requireNotNull(sqlExecutor) { "SQL Executor must be set" }
        requireNotNull(schemaDescription) { "Schema description must be provided" }

        val executor = sqlExecutor!!
        val llmProvider = provider!!
        val prompt = buildPrompt(this)

        return { _, conversation ->
            flow {
                var responseContent: String? = null
                var generatedSql: GeneratedSql? = null
                var predefinedSelection: PreDefinedQuerySelection? = null

                try {
                    emit(SystemResult("Requesting SQL generation from LLM..."))
                    val options = LLMOptions(
                        responseFormat = "json_object",
                        systemPrompt = prompt,
                        temperature = 0.1
                    )

                    val response = llmProvider.generate(conversation.messages, options)
                        .toList()
                        .lastOrNull { it is LLMMessage.AssistantMessage }
                        ?: throw Exception("LLM did not return an AssistantMessage")

                    responseContent = (response as LLMMessage.AssistantMessage).content
                    emit(SystemResult("Received LLM response, parsing..."))

                    when (queryGenerationRule) {
                        QueryGenerationRule.UNRESTRICTED, QueryGenerationRule.LOOSE -> {
                            generatedSql = json.decodeFromString<GeneratedSql>(responseContent)
                        }
                        QueryGenerationRule.STRICT -> {
                            predefinedSelection = json.decodeFromString<PreDefinedQuerySelection>(responseContent)
                        }
                    }

                } catch (e: Exception) {
                    val errorMsg = "Error during SQL generation/parsing: ${e.message}. Raw Response: ${responseContent?.take(200)}..."
                    emit(ErrorResult(e, errorMsg))
                    return@flow
                }

                val sqlToExecute: GeneratedSql? = when {
                    generatedSql != null -> generatedSql
                    predefinedSelection != null -> {
                        val query = predefinedQueries[predefinedSelection.queryId]
                        if (query != null) {
                            emit(SystemResult("Using predefined query #${predefinedSelection.queryId}: ${query.description}"))
                            GeneratedSql(query.sqlTemplate, predefinedSelection.parameters)
                        } else {
                            emit(ErrorResult(null, "Selected predefined query #${predefinedSelection.queryId} not found."))
                            null
                        }
                    }
                    else -> null
                }

                if (sqlToExecute == null) {
                    if (!currentCoroutineContext().isActive) return@flow
                    emit(ErrorResult(error = null, "Could not determine SQL to execute after parsing LLM response."))
                    return@flow
                }

                try {
                    val finalSql = sqlToExecute.sqlTemplate
                    emit(SystemResult("Executing SQL: `$finalSql` with params: ${sqlToExecute.parameters.joinToString { it.value.toString()  }}"))

                    val result = executor.execute(sqlToExecute)

                    when (result) {
                        is SqlResult.Success -> {
                            emit(StructuredResult(data = result, rawMessage = null, rawContent = null))
                            val formattedString = formatResults(result)
                            emit(TextResult(formattedString))
                        }
                        is SqlResult.Error -> {
                            emit(ErrorResult(null, "Error executing SQL: ${result.message}"))
                        }
                    }
                } catch (e: Exception) {
                    emit(ErrorResult(e, "Failed to execute SQL: ${e.message}"))
                }
            }
        }
    }
}

/**
 * Builds a prompt for the LLM to generate a SQL query.
 */
private fun buildPrompt(builder: DatabaseAgentBuilder): String {
    val dialect = builder.dialect
    val predefinedQueries = builder.predefinedQueries
    val mode = builder.queryGenerationRule
    val schemaDescription = builder.schemaDescription

    val tablesDdl = schemaDescription

    val predefinedQueriesText = if (predefinedQueries.isNotEmpty()) {
        """
        **Predefined Queries (For Reference):**
        ${
            predefinedQueries.entries.joinToString("\n") { (id, query) ->
                """
            Query #$id: ${query.description}
            Parameters: ${
                    if (query.parameterDescriptions.isEmpty()) "None" else query.parameterDescriptions.joinToString(
                        ", "
                    )
                }
            SQL: ${query.sqlTemplate}
            """
                    .trimIndent()
            }
        }
        """
    } else {
        ""
    }

    val modeSpecificInstructions = when (mode) {
        QueryGenerationRule.UNRESTRICTED -> {
            """
            **Instructions for Custom SQL:**
            Generate a JSON object containing:
            - `"sql_template"`: A string containing the SQL query template. Use standard JDBC placeholders (`?`) for all user-provided values or literals derived from the request.
            - `"parameters"`: A JSON array of objects, each containing:
              - `"value"`: The parameter value as a JSON primitive (string, number, boolean, null). Ensure strings containing 'null' are treated as the string null, not the JSON null type unless intended.
              - `"type"`: The *intended* SQL type: "STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "TIMESTAMP".
            - `"column_names"`: **Required for SELECT queries, null otherwise.** A JSON array of strings representing the exact column names or aliases expected in the result set, in the order they appear in the SELECT clause. Example: ["user_id", "name", "total_orders"]. If using `SELECT *`, list all relevant columns from the table(s).

            For date and timestamp parameter values:
            - DATE must be in ISO-8601 format: "yyyy-MM-dd" (e.g., "2024-01-01")
            - TIMESTAMP must be in ISO-8601 format: "yyyy-MM-ddTHH:mm:ss" or "yyyy-MM-dd HH:mm:ss" (e.g., "2024-01-01T13:45:30")

            Example (SELECT): {
              "sql_template": "SELECT id, name, age FROM users WHERE age > ? AND join_date > ?",
              "parameters": [
                {"value": 18, "type": "INTEGER"},
                {"value": "2024-01-01", "type": "DATE"}
              ],
              "column_names": ["id", "name", "age"]
            }

            Example (INSERT): {
              "sql_template": "INSERT INTO products (name, price) VALUES (?, ?)",
              "parameters": [
                {"value": "Gadget", "type": "STRING"},
                {"value": 99.95, "type": "DECIMAL"}
              ],
              "column_names": null
            }
            """
        }

        QueryGenerationRule.STRICT -> {
            if (predefinedQueries.isEmpty()) {
                "// STRICT mode selected, but no predefined queries are available."
            } else {
                predefinedQueriesText +
                        "\n\n**Instruction:** Select the most appropriate Query #id and provide necessary parameters based on the user request. Respond ONLY with the JSON object for PreDefinedQuerySelection: {\"queryId\": <id>, \"parameters\": [{...}]}"
            }
        }

        QueryGenerationRule.LOOSE -> {
            (if (predefinedQueries.isNotEmpty()) predefinedQueriesText + "\n\n" else "") +
                    """
            **Instructions for Custom SQL:**
            Generate a JSON object containing:
            - `"sql_template"`: A string containing the SQL query template. Use standard JDBC placeholders (`?`) for all user-provided values or literals derived from the request.
            - `"parameters"`: A JSON array of objects, each containing:
              - `"value"`: The parameter value as a JSON primitive (string, number, boolean, null). Ensure strings containing 'null' are treated as the string null, not the JSON null type unless intended.
              - `"type"`: The *intended* SQL type: "STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "TIMESTAMP".
            - `"column_names"`: **Required for SELECT queries, null otherwise.** A JSON array of strings representing the exact column names or aliases expected in the result set, in the order they appear in the SELECT clause. Example: ["user_id", "name", "total_orders"]. If using `SELECT *`, list all relevant columns from the table(s).

            For date and timestamp parameter values:
            - DATE must be in ISO-8601 format: "yyyy-MM-dd" (e.g., "2024-01-01")
            - TIMESTAMP must be in ISO-8601 format: "yyyy-MM-ddTHH:mm:ss" or "yyyy-MM-dd HH:mm:ss" (e.g., "2024-01-01T13:45:30")

            Example (SELECT): {
              "sql_template": "SELECT id, name, age FROM users WHERE age > ? AND join_date > ?",
              "parameters": [
                {"value": 18, "type": "INTEGER"},
                {"value": "2024-01-01", "type": "DATE"}
              ],
              "column_names": ["id", "name", "age"]
            }

            Example (INSERT): {
              "sql_template": "INSERT INTO products (name, price) VALUES (?, ?)",
              "parameters": [
                {"value": "Gadget", "type": "STRING"},
                {"value": 99.95, "type": "DECIMAL"}
              ],
              "column_names": null
            }
            """
        }
    }

    return """
    You are an AI assistant that translates natural language questions into database queries for a $dialect database.

    **Database Schema:**
    ```sql
    $tablesDdl
    ```

    $modeSpecificInstructions

    Analyze the user's request and the database schema.
    Output **only** the raw JSON object representing the query (either GeneratedSql or PreDefinedQuerySelection format based on instructions), without any surrounding text, explanations, or markdown formatting.
    """.trimIndent()
}

/**
 * Formats successful SQL results into a string table.
 */
private fun formatResults(result: SqlResult.Success): String {
    if (result.rows.isEmpty()) return "Query executed successfully, no rows returned."

    val headers = result.headers
    if (headers.isEmpty() && result.rows.isNotEmpty() && result.rows.first().size == 1) {
        return result.rows.first().first()?.toString() ?: "Query executed."
    }
    if (headers.isEmpty()) return "Query executed, but no column headers were provided/returned."

    val colWidths = headers.associateWith { header ->
        maxOf(
            header.length,
            result.rows.maxOfOrNull { row ->
                val index = headers.indexOf(header)
                (row.getOrNull(index)?.toString() ?: "null").length
            } ?: 0,
        )
    }

    return buildString {
        appendLine(headers.joinToString(" | ") { it.padEnd(colWidths.getValue(it)) })
        appendLine(headers.joinToString("-+-") { "-".repeat(colWidths.getValue(it)) })
        result.rows.forEach { row ->
            appendLine(
                headers.joinToString(" | ") { header ->
                    val index = headers.indexOf(header)
                    (row.getOrNull(index)?.toString() ?: "null").padEnd(colWidths.getValue(header))
                },
            )
        }
    }
}
