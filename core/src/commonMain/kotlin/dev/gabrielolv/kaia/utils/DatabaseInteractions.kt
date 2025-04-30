package dev.gabrielolv.kaia.utils

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
