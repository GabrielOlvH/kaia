package dev.gabrielolv.kaia.utils

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import dev.gabrielolv.kaia.core.agents.DatabaseAgentBuilder
import dev.gabrielolv.kaia.core.agents.DatabaseQueryListener
import dev.gabrielolv.kaia.llm.LLMMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

enum class QueryGenerationRule {
    UNRESTRICTED,
    STRICT,
    LOOSE,
}

enum class SqlParameterType {
    STRING,
    INTEGER,
    DECIMAL,
    BOOLEAN,
    DATE,
    TIMESTAMP,
}

@Serializable
data class SqlParameter(
    val value: JsonPrimitive,
    val type: SqlParameterType,
)

/**
 * Represents a SQL query with parameterized values, types,
 * and expected column names for SELECT queries.
 */
@Serializable
data class GeneratedSql(
    val sqlTemplate: String,
    val parameters: List<SqlParameter> = emptyList(),
    // Column names are required for SELECT queries to map results correctly.
    val columnNames: List<String>? = null,
)

@Serializable
data class PreDefinedQuerySelection(
    val queryId: Int,
    val parameters: List<SqlParameter> = emptyList(),
)

@Serializable
data class PreDefinedQuery(
    val sqlTemplate: String,
    val description: String,
    val parameterDescriptions: List<String> = emptyList(),
)

// --- Helper Functions ---

/**
 * Converts a parameter value from JsonPrimitive to a type suitable for
 * SQLDelight's binding functions.
 */
private fun prepareParameter(param: SqlParameter): Any? {
    // Handle explicit nulls represented as the string "null"
    if (param.value.isString &&
        param.value.content.equals("null", ignoreCase = true)
    ) {
        return null
    }

    return when (param.type) {
        SqlParameterType.STRING -> param.value.content
        // SQLDelight generally uses Long for integer types
        SqlParameterType.INTEGER -> param.value.content.toLongOrNull()
        // SQLDelight generally uses Double for real types
        SqlParameterType.DECIMAL -> param.value.content.toDoubleOrNull()
        SqlParameterType.BOOLEAN -> param.value.content.toBooleanStrictOrNull()
        // Keep Date/Timestamp as String; rely on SQLDelight adapters in user code
        SqlParameterType.DATE -> param.value.content
        SqlParameterType.TIMESTAMP -> param.value.content
    }
}

/**
 * Formats query results (List<Map<String, Any?>>) into a readable string table.
 */
private fun formatResults(rows: List<Map<String, Any?>>): String {
    if (rows.isEmpty()) return "Query executed successfully, no rows returned."

    // Use the keys from the first row as headers. Assumes all rows have the same keys.
    val headers = rows.first().keys.toList()
    if (headers.isEmpty()) return "Query executed, but no columns were returned."

    // Calculate column widths for better formatting (optional but nice)
    val colWidths = headers.associateWith { header ->
        maxOf(
            header.length,
            rows.maxOf { row -> (row[header]?.toString() ?: "null").length },
        )
    }

    return buildString {
        // Header row
        appendLine(
            headers.joinToString(" | ") { it.padEnd(colWidths.getValue(it)) },
        )
        // Separator line
        appendLine(
            headers.joinToString("-+-") {
                "-".repeat(colWidths.getValue(it))
            },
        )
        // Data rows
        rows.forEach { row ->
            appendLine(
                headers.joinToString(" | ") { header ->
                    (row[header]?.toString() ?: "null").padEnd(
                        colWidths.getValue(header),
                    )
                },
            )
        }
    }
}

// --- Core Execution Logic ---

/**
 * Executes a dynamically generated SQL query using the SQLDelight driver.
 *
 * IMPORTANT: For SELECT queries, `generatedSql.columnNames` MUST be provided
 * by the LLM to correctly map the results, as SqlCursor does not expose names.
 */
fun execute(
    driver: SqlDriver,
    generatedSql: GeneratedSql,
    listener: DatabaseQueryListener?,
): Flow<LLMMessage> = flow {
    var resultRows: List<Map<String, Any?>>? = null
    var executionError: Exception? = null
    val sql = generatedSql.sqlTemplate.trim()

    try {
        val isSelect = sql.startsWith("SELECT", ignoreCase = true)

        // Prepare the binder function for parameters
        val binder: (SqlPreparedStatement) -> Unit = { stmt ->
            generatedSql.parameters.forEachIndexed { index, param ->
                val preparedValue = prepareParameter(param)
                val sqlIndex = index + 1 // SQL parameter indices are 1-based

                // Bind based on the determined type
                when (preparedValue) {
                    is String -> stmt.bindString(sqlIndex, preparedValue)
                    is Long -> stmt.bindLong(sqlIndex, preparedValue)
                    is Double -> stmt.bindDouble(sqlIndex, preparedValue)
                    // Booleans are often mapped to Long (0 or 1) in SQLDelight drivers
                    is Boolean -> stmt.bindLong(sqlIndex, if (preparedValue) 1L else 0L)
                    null -> stmt.bindString(sqlIndex, null)
                    else -> {
                        // Fallback for unexpected types (e.g., custom types for Date/Timestamp
                        // if adapters aren't used upstream, though prepareParameter handles them as String)
                        // This might need adjustment based on specific needs.
                        println(
                            "Warning: Binding parameter $sqlIndex with unexpected type ${preparedValue::class.simpleName}, using toString()",
                        )
                        stmt.bindString(sqlIndex, preparedValue.toString())
                    }
                }
            }
        }

        if (isSelect) {
            // Ensure column names are provided for SELECT queries
            val columnNames = generatedSql.columnNames
            if (columnNames == null || columnNames.isEmpty()) {
                throw IllegalArgumentException(
                    "Column names must be provided in GeneratedSql for SELECT queries.",
                )
            }

            // Define the mapper function inline
            val mapper: (SqlCursor) -> QueryResult<List<Map<String, Any?>>> = { cursor ->
                val rows = mutableListOf<Map<String, Any?>>()
                while (cursor.next().value) {
                    val row = mutableMapOf<String, Any?>()
                    // Map columns based on the *provided* names and their index
                    columnNames.forEachIndexed { index, columnName ->
                        // Attempt to get the most specific type possible from the cursor,
                        // falling back to String. This is still a limitation of raw cursor access.
                        // SQLDelight's generated mappers are superior as they know the schema type.
                        val value: Any? = try {
                            // Order matters: check potentially null types first
                            cursor.getLong(index) ?: cursor.getDouble(index)
                            ?: cursor.getString(index) ?: cursor.getBytes(index)
                        } catch (e: Exception) {
                            // Fallback if specific type getters fail (might happen with complex types)
                            try {
                                cursor.getString(index)
                            } catch (e2: Exception) {
                                // If even getString fails, record as an error indicator
                                "Error reading column $index"
                            }
                        }
                        row[columnName] = value
                    }
                    rows.add(row)
                }
                QueryResult.Value(rows)
            }

            // Execute the query and map results
            resultRows = driver.executeQuery(
                identifier = null, // Identifier not needed for raw SQL
                sql = sql,
                mapper = mapper,
                parameters = generatedSql.parameters.size,
                binders = binder,
            ).value
        } else {
            // Execute non-SELECT statement (INSERT, UPDATE, DELETE, etc.)
            val rowsAffected = driver.execute(
                identifier = null,
                sql = sql,
                parameters = generatedSql.parameters.size,
                binders = binder,
            )
            // Provide feedback about rows affected for non-select queries
            resultRows = listOf(mapOf("rows_affected" to rowsAffected.value))
        }
    } catch (e: Exception) {
        // Catch potential errors during preparation or execution
        println("Error executing SQL: ${e.message}") // Log the error
        e.printStackTrace() // Print stack trace for debugging
        executionError = e
    }

    // --- Emit results or error message ---
    val message = when {
        executionError != null -> LLMMessage.SystemMessage(
            "There was an error executing the query: ${executionError.message}",
        )
        resultRows != null -> {
            val resultsText = formatResults(resultRows)
            LLMMessage.SystemMessage("Query Results:\n$resultsText")
        }
        else -> LLMMessage.SystemMessage(
            "Query execution did not produce results or encountered an unexpected state.",
        )
    }
    emit(message)

    // --- Notify listener (if provided) ---
    listener?.let {
        it(
            generatedSql.sqlTemplate,
            generatedSql.parameters,
            resultRows, // Pass the actual results
            executionError,
        ).collect { msg -> emit(msg) }
    }
}

// --- Prompt Building (Requires LLM Update) ---

/**
 * Builds a prompt for the LLM to generate a SQL query.
 * **IMPORTANT**: The LLM must now also return `column_names` for SELECT queries.
 */
fun buildPrompt(builder: DatabaseAgentBuilder): String {
    val dialect = builder.dialect!!
    val predefinedQueries = builder.predefinedQueries
    val mode = builder.queryGenerationRule
    val schemaDescription = builder.schemaDescription!!

    val tablesDdl = schemaDescription

    val predefinedQueriesText = if (predefinedQueries.isNotEmpty()) {
        """
        **Predefined Queries (For Reference):**
        ${predefinedQueries.entries.joinToString("\n") { (id, query) ->
            """
            Query #$id: ${query.description}
            Parameters: ${if (query.parameterDescriptions.isEmpty()) "None" else query.parameterDescriptions.joinToString(", ")}
            SQL: ${query.sqlTemplate}
            """
                .trimIndent()
        }}
        """
    } else {
        ""
    }

    // Updated instructions for the LLM
    val customQueryInstructions = """
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

    val modeSpecificInstructions = when (mode) {
        QueryGenerationRule.UNRESTRICTED -> {
            customQueryInstructions
        }
        QueryGenerationRule.STRICT -> {
            if (predefinedQueries.isEmpty()) {
                // Consider if this should be an error or just a limitation message
                "// STRICT mode selected, but no predefined queries are available."
            } else {
                predefinedQueriesText +
                        "\n\n**Instruction:** Select the most appropriate Query #id and provide necessary parameters based on the user request."
            }
        }
        QueryGenerationRule.LOOSE -> {
            // Combine predefined reference with custom generation instructions
            (if (predefinedQueries.isNotEmpty()) predefinedQueriesText + "\n\n" else "") +
                    customQueryInstructions +
                    "\n\nUse predefined queries as examples/reference if helpful, but generate a custom SQL JSON based on the user's specific request."
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
