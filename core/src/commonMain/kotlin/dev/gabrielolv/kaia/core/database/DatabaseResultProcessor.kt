package dev.gabrielolv.kaia.core.database

import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.utils.GeneratedSql
import kotlinx.coroutines.flow.FlowCollector

/**
 * Processes the result of a successful database query execution, formatting it for display.
 * Implementations can provide custom formatting logic.
 */
fun interface DatabaseResultProcessor {
    /**
     * Formats the SQL execution result into a user-friendly string.
     *
     * @param executedQuery The `GeneratedSql` object that was executed (providing context like SQL template and parameters).
     * @param result The `SqlResult.Success` object containing the query results (headers and rows).
     * @return A string representation of the result suitable for the user.
     */
    suspend fun processResult(flow: FlowCollector<LLMMessage>, executedQuery: GeneratedSql, result: SqlResult.Success)
}