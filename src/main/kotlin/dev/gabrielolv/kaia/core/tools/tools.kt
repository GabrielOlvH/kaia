package dev.gabrielolv.kaia.core.tools

import dev.gabrielolv.kaia.core.tools.builders.createTool
import dev.gabrielolv.kaia.core.tools.typed.ToolParameters
import dev.gabrielolv.kaia.core.tools.typed.ToolParametersInstance
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.javatime.*
import java.math.BigDecimal
import java.sql.SQLException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

object SqlParameterParams : ToolParameters() {
    val value = string("value")
        .withDescription("The parameter value.")
    val type = string("type")
        .withDescription("The SQL type (e.g., VARCHAR, INTEGER, DATE, TIMESTAMP, BOOLEAN, DECIMAL, BLOB).")

    init {
        required(value, type)
    }
}

object TypedSqlExecutionParams : ToolParameters() {
    val sqlTemplate = string("sql_template")
        .withDescription("The parameterized SQL query template with '?' placeholders.")
    val parameters = objectList("parameters", SqlParameterParams::class)
        .withDescription("A list of parameter objects, each specifying a value and its SQL type.")

    init {
        required(sqlTemplate)
    }
}

private val typeMap: Map<String, IColumnType<*>> = mapOf(
    // Text
    "VARCHAR" to VarCharColumnType(),
    "TEXT" to TextColumnType(),
    "CHAR" to CharColumnType(),
    // Integer Types
    "INTEGER" to IntegerColumnType(),
    "INT" to IntegerColumnType(),
    "BIGINT" to LongColumnType(),
    "LONG" to LongColumnType(),
    "SMALLINT" to ShortColumnType(),
    // Decimal Types
    "DECIMAL" to DecimalColumnType(38, 10), // Default precision/scale
    "NUMERIC" to DecimalColumnType(38, 10), // Default precision/scale
    "DOUBLE" to DoubleColumnType(),
    "FLOAT" to FloatColumnType(), // Exposed Float maps to java.lang.Float
    // Boolean
    "BOOLEAN" to BooleanColumnType(),
    "BIT" to BooleanColumnType(), // Common mapping
    // Date/Time (using Exposed Java Time support)
    "DATE" to JavaLocalDateColumnType(), // time=false
    "DATETIME" to JavaLocalDateTimeColumnType(), // Use DATETIME for LocalDateTime
    // Binary
    "BLOB" to BlobColumnType(),
    "BINARY" to BinaryColumnType(Int.MAX_VALUE), // Specify length or use default
    // Special
    "NULL" to VarCharColumnType()
).mapKeys { it.key.uppercase() }

private fun getExposedColumnTypeFromString(typeString: String): IColumnType<*> {
    return typeMap[typeString.uppercase()]
        ?: throw IllegalArgumentException("Unsupported SQL type string for Exposed mapping: $typeString")
}
private fun convertStringToTypedValue(
    valueString: String?,
    targetType: IColumnType<*>
): Any? {
    if (valueString == null) {
        return null
    }

    try {
        return when (targetType) {
            // Text types: Already a string
            is VarCharColumnType, is TextColumnType, is CharColumnType -> valueString
            // Integer types: Parse from string
            is IntegerColumnType -> valueString.toInt()
            is LongColumnType -> valueString.toLong()
            is ShortColumnType -> valueString.toShort()
            // Decimal types: Parse from string
            is DecimalColumnType -> BigDecimal(valueString)
            is DoubleColumnType -> valueString.toDouble()
            is FloatColumnType -> valueString.toFloat()
            // Boolean type: Parse from string (handle common cases)
            is BooleanColumnType -> when (valueString.lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> throw IllegalArgumentException("Invalid boolean format '$valueString'. Use true/false.")
            }
            // Date/Time types: Parse from string using ISO formats
            is JavaLocalDateColumnType -> LocalDate.parse(valueString, DateTimeFormatter.ISO_LOCAL_DATE)
            is JavaLocalDateTimeColumnType -> LocalDateTime.parse(valueString, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            is JavaInstantColumnType -> { // Use JavaInstantColumnType for TIMESTAMP
                // Try parsing as OffsetDateTime first, then LocalDateTime assuming system default zone
                try {
                    OffsetDateTime.parse(valueString, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
                } catch (e: DateTimeParseException) {
                    LocalDateTime.parse(valueString, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        .atZone(ZoneOffset.systemDefault()).toInstant() // Or specify UTC: ZoneOffset.UTC
                }
            }
            is BlobColumnType -> ExposedBlob(Base64.getDecoder().decode(valueString))
            is BinaryColumnType -> Base64.getDecoder().decode(valueString)

             is UUIDColumnType -> UUID.fromString(valueString)
            else -> valueString
        }
    } catch (e: NumberFormatException) {
        throw IllegalArgumentException("Invalid number format '$valueString' for target type ${targetType::class.simpleName}.", e)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("Invalid date/time format '$valueString' for target type ${targetType::class.simpleName}. Use ISO format.", e)
    } catch (e: IllegalArgumentException) {
        // Catch specific errors like invalid boolean or Base64
        throw IllegalArgumentException("Invalid format '$valueString' for target type ${targetType::class.simpleName}: ${e.message}", e)
    } catch (e: Exception) {
        // Catch other potential conversion errors
        throw IllegalArgumentException("Error converting value '$valueString' to target type ${targetType::class.simpleName}: ${e.message}", e)
    }
}


private fun runQueryInternal(
    database: Database,
    sqlTemplate: String,
    parameters: List<ToolParametersInstance>
): Pair<String?, String?> { // Pair<SuccessResult, ErrorMessage>

    var resultRows: List<Map<String, Any?>>? = null
    var executionError: Throwable? = null

    transaction(database) {
        val connection = this.connection

        try {
            val stmt = connection.prepareStatement(sqlTemplate, false)
            stmt.fillParameters(parameters.map {
                val type = getExposedColumnTypeFromString(it[SqlParameterParams.type])
                val value = convertStringToTypedValue(it[SqlParameterParams.value], type)
                type to value
            })

            stmt.executeQuery().use { rs ->
                val metaData = rs.metaData
                val columnCount = metaData.columnCount
                val columnNames = (1..columnCount).map { metaData.getColumnLabel(it) }
                val rowsData = mutableListOf<Map<String, Any?>>()
                while (rs.next()) {
                    val row = mutableMapOf<String, Any?>()
                    for (i in 1..columnCount) {
                        row[columnNames[i - 1]] = rs.getObject(i)
                    }
                    rowsData.add(row)
                }
                resultRows = rowsData
            }

        } catch (e: Exception) {
            executionError = e
        }
    }

    executionError?.let {
        val message = when (it) {
            is IllegalArgumentException ->
                "Error processing parameters: ${it.message}"
            is SQLException -> "SQL Execution Error: ${it.message} (SQLState: ${it.sqlState})"
            else -> "Error during query execution: ${it.message}"
        }
        return null to message
    }

    resultRows?.let { rows ->
        if (rows.isEmpty()) {
            return "Query executed successfully, but returned no results." to null
        }
        val resultsAsString = rows.joinToString("\n") { entry ->
            entry.entries.joinToString { (k, v) -> "$k=${v ?: "NULL"}" } // Handle null display
        }
        return "Query Results:\n$resultsAsString" to null
    }

    return null to "An unexpected state occurred after query execution."
}

fun createSqlTool(
    database: Database,
    toolName: String = "sql_tool",
    toolDescription: String = "Executes SQL queries to retrieve data to fulfill the user's request."
): Tool {

    return createTool<TypedSqlExecutionParams> {
        name = toolName
        description = toolDescription

        execute { params ->
            val template = params[TypedSqlExecutionParams.sqlTemplate]
            val paramInstances = params[TypedSqlExecutionParams.parameters]

            try {
                val (queryResult, errorMessage) = runQueryInternal(
                    database,
                    template,
                    paramInstances
                )

                if (errorMessage != null) {
                    ToolResult(success = false, result = errorMessage)
                } else {
                    ToolResult(success = true, result = queryResult ?: "Query executed, but no specific result message.")
                }

            } catch (e: Exception) {
                ToolResult(
                    success = false,
                    result = "An unexpected error occurred during tool execution: ${e.message}"
                )
            }
        }
    }
}
