package dev.gabrielolv.kaia.utils

import dev.gabrielolv.kaia.core.agents.DatabaseAgentBuilder
import dev.gabrielolv.kaia.core.agents.DatabaseQueryListener
import dev.gabrielolv.kaia.llm.LLMMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.ResultSet

/**
 * Defines the operating mode for database agents.
 */
enum class QueryGenerationRule {
    /** Agent can generate any SQL query */
    UNRESTRICTED,
    /** Agent can only use predefined queries */
    STRICT,
    /** Agent can use predefined queries or generate custom queries */
    LOOSE
}

/**
 * Represents supported SQL parameter types.
 */
enum class SqlParameterType {
    STRING, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP
}

/**
 * Represents a SQL parameter with its value and type.
 */
@Serializable
data class SqlParameter(
    val value: JsonPrimitive,
    val type: SqlParameterType
)

/**
 * Represents a SQL query with parameterized values and their types.
 */
@Serializable
data class GeneratedSql(
    val sqlTemplate: String,
    val parameters: List<SqlParameter> = emptyList()
)

/**
 * Represents the selection of a predefined query with parameters.
 */
@Serializable
data class PreDefinedQuerySelection(
    val queryId: Int,
    val parameters: List<SqlParameter> = emptyList()
)

/**
 * Defines a predefined query with its description and parameter details.
 */
@Serializable
data class PreDefinedQuery(
    val sqlTemplate: String,
    val description: String,
    val parameterDescriptions: List<String> = emptyList()
)

// Cache for database table DDL statements
private val ddlCache = mutableMapOf<String, String>()

/**
 * Retrieves the DDL statements for the given tables.
 * Uses caching to avoid regenerating DDL for the same tables.
 */
fun getDdlForTables(tables: List<Table>): String {
    return tables.joinToString("\n") { table ->
        ddlCache.getOrPut(table.tableName) {
            val db = Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
            transaction(db) {
                table.ddl.joinToString("\n")
            }
        }
    }
}

/**
 * Converts a parameter value to the specified SQL type.
 */
private fun convertParameter(param: SqlParameter): Any? {
    if (param.value.isString && param.value.content.equals("null", ignoreCase = true)) {
        return null
    }
    
    return when (param.type) {
        SqlParameterType.STRING -> param.value.content
        SqlParameterType.INTEGER -> param.value.content.toInt()
        SqlParameterType.DECIMAL -> param.value.content.toBigDecimal()
        SqlParameterType.BOOLEAN -> param.value.content.toBoolean()
        SqlParameterType.DATE -> java.sql.Date.valueOf(param.value.content)
        SqlParameterType.TIMESTAMP -> java.sql.Timestamp.valueOf(param.value.content)
    }
}

/**
 * Executes a SQL query on the given database and returns the results as a flow of LLM messages.
 */
fun execute(database: Database, generatedSql: GeneratedSql, listener: DatabaseQueryListener?): Flow<LLMMessage> = flow {
    val (rows, error) = transaction(database) {
        try {
            val stmt = connection.prepareStatement(generatedSql.sqlTemplate, false)
            // Set parameters in prepared statement with type conversion
            generatedSql.parameters.forEachIndexed { index, param ->
                val convertedValue = convertParameter(param)
                if (convertedValue == null) {
                    stmt.setNull(index + 1, VarCharColumnType())
                } else {
                    stmt[index + 1] = convertedValue
                }
            }

            stmt.executeQuery().use { resultSet ->
                mapResultSetToRows(resultSet) to null
            }

        } catch (e: Exception) {
            null to e
        }
    }

    val defaultMessage = when {
        error != null -> LLMMessage.SystemMessage("There was an error while executing the query: ${error.message}")
        rows != null -> {
            val resultsText = formatResults(rows)
            LLMMessage.SystemMessage("Query Results:\n$resultsText")
        }
        else -> LLMMessage.SystemMessage("Unreachable. If you see this, cry.")
    }
    emit(defaultMessage)

    listener?.let {
        it(generatedSql.sqlTemplate, generatedSql.parameters, rows, error).collect { message ->
            emit(message)
        }
    }
}

/**
 * Maps a ResultSet to a list of maps where each map represents a row.
 */
private fun mapResultSetToRows(resultSet: ResultSet): List<Map<String, Any?>> {
    val metaData = resultSet.metaData
    val columnCount = metaData.columnCount
    val columnNames = (1..columnCount).map { metaData.getColumnLabel(it) }
    
    val rows = mutableListOf<Map<String, Any?>>()
    while (resultSet.next()) {
        val row = mutableMapOf<String, Any?>()
        for (i in 1..columnCount) {
            row[columnNames[i - 1]] = resultSet.getObject(i)
        }
        rows.add(row)
    }
    return rows
}

/**
 * Formats query results into a readable string.
 */
private fun formatResults(rows: List<Map<String, Any?>>): String {
    if (rows.isEmpty()) return "No results"

    val headers = rows.first().keys
    return buildString {
        appendLine(headers.joinToString("|"))
        appendLine("-".repeat(headers.size * 12))
        // Data rows
        rows.forEach { row ->
            appendLine(headers.joinToString("|") { row[it]?.toString() ?: "null" })
        }
    }
}


fun buildPrompt(builder: DatabaseAgentBuilder): String {
    val database = builder.database!!
    val predefinedQueries = builder.predefinedQueries
    val mode = builder.queryGenerationRule
    val tables = builder.tables

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
        QueryGenerationRule.UNRESTRICTED -> {
            customQueryInstructions
        }
        QueryGenerationRule.STRICT -> {
            if (predefinedQueries.isEmpty()) {
                throw IllegalArgumentException("PREDEFINED_ONLY mode requires at least one predefined query")
            }
            predefinedQueriesText
        }
        QueryGenerationRule.LOOSE -> {
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

    return """
    You are an AI assistant that translates natural language questions into database queries for a $dialect database.
    
    **Database Schema:**
    ```sql
    ${getDdlForTables(tables)}
    ```
    
    $modeSpecificInstructions
    
    Output **only** the raw JSON object, without any surrounding text or markdown formatting.
    """.trimIndent()
}