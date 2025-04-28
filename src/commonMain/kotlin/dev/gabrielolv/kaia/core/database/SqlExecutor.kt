package dev.gabrielolv.kaia.core.database

import dev.gabrielolv.kaia.utils.GeneratedSql

/**
 * Represents the result of executing an SQL query.
 */
sealed class SqlResult {
    /**
     * Indicates successful execution, containing column headers and data rows.
     * Rows are represented as lists of nullable values.
     */
    data class Success(val headers: List<String>, val rows: List<List<Any?>>) : SqlResult()

    /**
     * Indicates an error occurred during execution.
     */
    data class Error(val message: String) : SqlResult()
}

/**
 * Defines a standard interface for executing SQL queries.
 * Implementations can abstract different database connection libraries.
 */
interface SqlExecutor {
    /**
     * Executes the given SQL query, potentially with parameters.
     *
     * @param generatedSql An object containing the SQL template, parameters, and
     *                     expected column names (required for SELECT queries).
     * @return An [SqlResult] indicating success (with data) or failure (with an error message).
     */
    suspend fun execute(
        generatedSql: GeneratedSql
    ): SqlResult
}
