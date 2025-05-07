package dev.gabrielolv.kaia.core.agents

import dev.gabrielolv.kaia.core.Conversation
import dev.gabrielolv.kaia.core.database.SqlExecutor
import dev.gabrielolv.kaia.core.database.SqlResult
import dev.gabrielolv.kaia.core.model.*
import dev.gabrielolv.kaia.core.tenant.tenantContext
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

private const val CUSTOM_SQL_INSTRUCTIONS = """
**Instructions for Custom SQL Generation:**
Your goal is to construct a precise and valid SQL query based on the user's request and the provided database schema.

**JSON Output Structure (GeneratedSql):**
- `"sql_template"`: A string containing the SQL query template. Crucially, use standard JDBC placeholders (`?`) for ALL user-provided values or literals derived from the request to prevent SQL injection and ensure correctness.
- `"parameters"`: A JSON array of objects, each containing:
  - `"value"`: The parameter value as a JSON primitive (string, number, boolean, null). Ensure strings that look like 'null' are treated as the string "null", not the JSON `null` type unless specifically intended as a SQL NULL.
  - `"type"`: The *intended* SQL type: "STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "TIMESTAMP". This type helps the database driver handle the value correctly.
- `"column_names"`: **Required for SELECT queries, `null` otherwise.** A JSON array of strings representing the exact column names or aliases as they will appear in the result set, in the order they are specified in the SELECT clause. If using `SELECT *`, you must list all relevant columns from the table(s) involved. This is vital for data processing.

**Parameter Value Formatting:**
- **DATE:** Must be in ISO-8601 format: "yyyy-MM-dd" (e.g., "2024-01-01").
- **TIMESTAMP:** Must be in ISO-8601 format: "yyyy-MM-ddTHH:mm:ss" or "yyyy-MM-dd HH:mm:ss" (e.g., "2024-01-01T13:45:30").

**Example (SELECT):**
{
  "sql_template": "SELECT id, name, age FROM users WHERE age > ? AND join_date >= ?",
  "parameters": [
    {"value": 18, "type": "INTEGER"},
    {"value": "2023-01-01", "type": "DATE"}
  ],
  "column_names": ["id", "name", "age"]
}

**Example (INSERT):**
{
  "sql_template": "INSERT INTO products (name, price, created_at) VALUES (?, ?, ?)",
  "parameters": [
    {"value": "Super Gadget", "type": "STRING"},
    {"value": 129.99, "type": "DECIMAL"},
    {"value": "2024-07-15T10:00:00", "type": "TIMESTAMP"}
  ],
  "column_names": null
}
"""

/**
 * Builder for creating and configuring a [DatabaseAgent].
 */
class DatabaseAgentBuilder : AgentBuilder() {
    var provider: LLMProvider? = null
    var dialect: String? = null
    var sqlExecutor: SqlExecutor? = null
    var schemaDescription: String? = null
    var predefinedQueries: Map<Int, PreDefinedQuery> = emptyMap()
    var queryGenerationRule: QueryGenerationRule = QueryGenerationRule.STRICT_PREDEFINED_ONLY

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
                        QueryGenerationRule.ALLOW_CUSTOM_SQL -> {
                            generatedSql = json.decodeFromString<GeneratedSql>(responseContent)
                        }
                        QueryGenerationRule.STRICT_PREDEFINED_ONLY -> {
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
                    emit(ErrorResult(error = null, "Could not determine SQL to execute after parsing LLM response."))
                    return@flow
                }

                try {
                    val finalSql = sqlToExecute.sqlTemplate
                    emit(SystemResult("Executing SQL: `$finalSql` with params: ${sqlToExecute.parameters.joinToString { it.value.toString()  }}"))

                    val tenantContext = currentCoroutineContext().tenantContext()

                    if (tenantContext == null) {
                        emit(ErrorResult(null, "Tenant context is null. Cannot execute SQL."))
                        return@flow
                    }

                    val result = executor.execute(tenantContext, sqlToExecute)

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
        **Predefined Queries (Available for Reference or Selection):**
        You may use these as a basis for custom SQL if in ALLOW_CUSTOM_SQL mode, or you MUST select one if in STRICT_PREDEFINED_ONLY mode.
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
        QueryGenerationRule.ALLOW_CUSTOM_SQL -> {
            (if (predefinedQueries.isNotEmpty()) predefinedQueriesText + "\n\n" else "") +
            CUSTOM_SQL_INSTRUCTIONS
        }
        QueryGenerationRule.STRICT_PREDEFINED_ONLY -> {
            if (predefinedQueries.isEmpty()) {
                "// CRITICAL_ERROR: STRICT_PREDEFINED_ONLY mode selected, but NO predefined queries are available. This is a configuration error. Cannot proceed."
            } else {
                predefinedQueriesText +
                        "\n\n**Instruction for STRICT_PREDEFINED_ONLY Mode:**\n" +
                        "Based on the user's request, you MUST select the most appropriate Query #id from the list above. " +
                        "Respond ONLY with the JSON object for PreDefinedQuerySelection: {\"queryId\": <id>, \"parameters\": [{...value..., ...type...}]}. " +
                        "Ensure all required parameters for the selected query are provided."
            }
        }
    }

    return """
    You are a highly intelligent AI assistant specializing in translating natural language questions into precise and efficient $dialect database queries.
    Your primary goal is to generate accurate SQL or select the correct predefined query, strictly adhering to the specified output format.

    **Core Task:** Analyze the user's request and the provided database schema to formulate a response.

    **Database Schema:**
    ```sql
    $tablesDdl
    ```

    **Your Thought Process & Guidelines:**
    1.  **Deeply Understand the Request:** Carefully parse the user's natural language question to fully grasp their intent and the specific data they are asking for.
    2.  **Consult the Schema:** Meticulously examine the `Database Schema` provided above. Identify all relevant tables, columns, data types, and relationships necessary to fulfill the user's request.
    3.  **Adhere to Operational Mode:**
        *   If the mode is `ALLOW_CUSTOM_SQL`: Generate a custom SQL query. You can draw inspiration from `Predefined Queries` if they are relevant.
        *   If the mode is `STRICT_PREDEFINED_ONLY`: You MUST select a query from the `Predefined Queries` list. Do not generate new SQL.
    4.  **Parameterize All Values (for Custom SQL):** Crucially, if generating custom SQL, all literal values derived from the user's request (e.g., names, numbers, dates) MUST be replaced with JDBC `?` placeholders in the `sql_template`. The actual values and their types must be listed in the `parameters` array. This is essential for security and correctness.
    5.  **Specify Column Names (for Custom SELECT SQL):** For custom `SELECT` queries, the `column_names` field in your JSON output is mandatory and must accurately list the names of the columns in the order they appear in your `SELECT` clause.
    6.  **Match Parameter Types:** Ensure the `type` specified for each parameter in the `parameters` array accurately reflects the intended SQL data type for the corresponding `value`. Refer to `CUSTOM_SQL_INSTRUCTIONS` for type names and formatting.

    $modeSpecificInstructions

    CRITICAL: Your entire response MUST be ONLY the raw JSON object as specified in the instructions (either `GeneratedSql` or `PreDefinedQuerySelection` format based on the operational mode).
    Do NOT include any other text, explanations, apologies, or markdown formatting (e.g., ```json ... ```) around the JSON object.
    The response must be directly parsable as JSON.
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
