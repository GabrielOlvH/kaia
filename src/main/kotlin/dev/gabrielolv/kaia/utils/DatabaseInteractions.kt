package dev.gabrielolv.kaia.utils

import dev.gabrielolv.kaia.llm.LLMMessage
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
enum class DatabaseAgentMode {
    /** Agent can generate any SQL query */
    FREE,
    /** Agent can only use predefined queries */
    PREDEFINED_ONLY,
    /** Agent can use predefined queries or generate custom queries */
    HYBRID
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
 * Executes a SQL query on the given database and returns the results as an LLM message.
 */
fun execute(database: Database, generatedSql: GeneratedSql): LLMMessage {
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

            // Execute and process results
            stmt.executeQuery().use { resultSet ->
                mapResultSetToRows(resultSet) to null
            }

        } catch (e: Exception) {
            null to e
        }
    }

    return when {
        error != null -> LLMMessage.SystemMessage("There was an error while executing the query: ${error.message}")
        rows != null -> {
            val resultsText = formatResults(rows)
            LLMMessage.SystemMessage("Query Results:\n$resultsText")
        }

        else -> LLMMessage.SystemMessage("Unreachable. If you see this, cry.")
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
