package dev.gabrielolv.kaia.core.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import dev.gabrielolv.kaia.utils.GeneratedSql
import dev.gabrielolv.kaia.utils.SqlParameter
import dev.gabrielolv.kaia.utils.SqlParameterType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A default implementation of [SqlExecutor] using SQLDelight's [SqlDriver].
 * Adapts logic from the original DatabaseInteractions.kt.
 *
 * @property driver The SQLDelight driver instance.
 */
class SqlDelightExecutor(private val driver: SqlDriver) : SqlExecutor {

    override suspend fun execute(
        generatedSql: GeneratedSql
    ): SqlResult = withContext(Dispatchers.Default) {
        val sql = generatedSql.sqlTemplate.trim()
        val isSelect = sql.startsWith("SELECT", ignoreCase = true)


        val binder: (SqlPreparedStatement) -> Unit = { stmt ->
            generatedSql.parameters.forEachIndexed { index, param ->
                val preparedValue = prepareParameter(param)
                val sqlIndex = index + 1
                when (preparedValue) {
                    is String -> stmt.bindString(sqlIndex, preparedValue)
                    is Long -> stmt.bindLong(sqlIndex, preparedValue)
                    is Double -> stmt.bindDouble(sqlIndex, preparedValue)
                    is Boolean -> stmt.bindLong(sqlIndex, if (preparedValue) 1L else 0L)
                    null -> stmt.bindString(sqlIndex, null)
                    else -> {
                        println("Warning: Binding parameter $sqlIndex with unexpected type ${preparedValue::class.simpleName}, using toString()")
                        stmt.bindString(sqlIndex, preparedValue.toString())
                    }
                }
            }
        }

        val result = try {
            if (isSelect) {
                val columnNames = generatedSql.columnNames
                if (columnNames == null || columnNames.isEmpty()) {
                    return@withContext SqlResult.Error("Column names must be provided in GeneratedSql for SELECT queries.")
                }

                val mapper: (SqlCursor) -> QueryResult<SqlResult> = { cursor ->
                    val rows = mutableListOf<List<Any?>>()
                    val headers = columnNames
                    try {
                        while (cursor.next().value) {
                            val row = List(headers.size) { index ->
                                try {
                                    cursor.getLong(index)
                                        ?: cursor.getDouble(index)
                                        ?: cursor.getString(index)
                                        ?: cursor.getBytes(index)
                                } catch (e: Exception) {
                                    println("Warning: Error reading column $index (${headers.getOrNull(index)}): ${e.message}")
                                    null
                                }
                            }
                            rows.add(row)
                        }
                        QueryResult.Value(SqlResult.Success(headers, rows))
                    } catch (e: Exception) {
                        QueryResult.Value(SqlResult.Error("Error processing rows: ${e.message}"))
                    }
                }

                driver.executeQuery(null, sql, mapper, generatedSql.parameters.size, binder).value

            } else {
                val affectedRows = driver.execute(null, sql, generatedSql.parameters.size, binder).value
                SqlResult.Success(emptyList(), listOf(listOf("Affected rows: $affectedRows")))
            }
        } catch (e: Exception) {
            SqlResult.Error(e.message ?: "An unknown error occurred during SQL execution.")
        }

        result
    }
}

/**
 * Converts a parameter value from JsonPrimitive to a type suitable for
 * SQLDelight's binding functions. (Moved from DatabaseInteractions.kt)
 */
private fun prepareParameter(param: SqlParameter): Any? {
    if (param.value.isString &&
        param.value.content.equals("null", ignoreCase = true)
    ) {
        return null
    }

    return when (param.type) {
        SqlParameterType.STRING -> param.value.content
        SqlParameterType.INTEGER -> param.value.content.toLongOrNull()
        SqlParameterType.DECIMAL -> param.value.content.toDoubleOrNull()
        SqlParameterType.BOOLEAN -> param.value.content.toBooleanStrictOrNull()
        SqlParameterType.DATE, SqlParameterType.TIMESTAMP -> param.value.content
    }
}
