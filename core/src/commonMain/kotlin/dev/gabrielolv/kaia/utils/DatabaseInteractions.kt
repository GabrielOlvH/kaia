package dev.gabrielolv.kaia.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive


enum class SqlParameterType {
    // Text types
    STRING,
    CHAR,
    VARCHAR,
    TEXT,
    
    // Numeric types
    INTEGER,
    SMALLINT,
    BIGINT,
    DECIMAL,
    NUMERIC,
    REAL,
    DOUBLE,
    
    // Boolean type
    BOOLEAN,
    
    // Date/Time types
    DATE,
    TIME,
    TIMESTAMP,
    TIMESTAMPTZ,
    INTERVAL,
    
    // Binary types
    BYTEA,
    
    // Network types
    INET,
    CIDR,
    MACADDR,
    
    // Geometric types
    POINT,
    LINE,
    LSEG,
    BOX,
    PATH,
    POLYGON,
    CIRCLE,
    
    // JSON types
    JSON,
    JSONB,
    
    // Array type
    ARRAY,
    
    // UUID type
    UUID,
    
    // XML type
    XML,
    
    // Money type
    MONEY,
    
    // Other types
    BIT,
    VARBIT,
    TSVECTOR,
    TSQUERY,
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
