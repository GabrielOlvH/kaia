package dev.gabrielolv.kaia.utils

import dev.gabrielolv.kaia.llm.LLMMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
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
 * Represents a SQL query with parameterized values.
 */
@Serializable
data class GeneratedSql(
    val sqlTemplate: String,
    val parameters: List<JsonPrimitive> = emptyList()
)

/**
 * Represents the selection of a predefined query with parameters.
 */
@Serializable
data class PreDefinedQuerySelection(
    val queryId: Int,
    val parameters: List<JsonPrimitive> = emptyList()
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
 * Executes a SQL query on the given database and returns the results as an LLM message.
 */
fun execute(database: Database, generatedSql: GeneratedSql): LLMMessage {
    val (rows, error) = transaction(database) {
        try {
            val stmt = connection.prepareStatement(generatedSql.sqlTemplate, false)
            // Set parameters in prepared statement
            generatedSql.parameters.forEachIndexed { index, primitive ->
                stmt[index + 1] = primitive.content
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