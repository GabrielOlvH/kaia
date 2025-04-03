package dev.gabrielolv.kaia.core.agents

import dev.gabrielolv.kaia.llm.LLMMessage
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
private data class GeneratedSql(val sqlTemplate: String, val parameters: List<JsonPrimitive>)

@OptIn(ExperimentalSerializationApi::class)
private val json = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
}

private val ddlCache = mutableMapOf<String, String>()

private fun getDdlForTables(tables: List<Table>): String {
    return tables.joinToString("\n") { table ->
        ddlCache.computeIfAbsent(table.tableName) {
            val db = Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
            transaction(db) {
                table.ddl.joinToString("\n")
            }
        }
    }
}

private fun runQuery(database: Database, generatedSql: GeneratedSql): LLMMessage {
    val (rows, error) = transaction(database) {
        try {
            val stmt = connection.prepareStatement(generatedSql.sqlTemplate, false)
            generatedSql.parameters.forEachIndexed { index, primitive ->
                stmt[index + 1] = primitive.content
            }

            val resultSet = stmt.executeQuery()
            val metaData = resultSet.metaData
            val columnCount = metaData.columnCount
            val columnNames =
                (1..columnCount).map { metaData.getColumnLabel(it) }

            val rows = mutableListOf<Map<String, Any?>>()
            while (resultSet.next()) {
                val row = mutableMapOf<String, Any?>()
                for (i in 1..columnCount) {
                    row[columnNames[i - 1]] = resultSet.getObject(i)
                }
                rows.add(row)
            }
            resultSet.close()
            rows to null
        } catch (e: Exception) {
            null to e
        }
    }

    error?.let {
        return LLMMessage.SystemMessage("There was an error while executing the query: ${error.message}")
    }

    rows?.let {
        val resultsAsString =
            rows.joinToString("\n") { entry -> entry.entries.joinToString { "${it.key}=${it.value}" } }
        return LLMMessage.AssistantMessage("Query Results:\n$resultsAsString")
    }

    return LLMMessage.SystemMessage("Unreachable. If you see this, cry.")
}


fun Agent.Companion.withDatabaseAccess(
    provider: LLMProvider,
    database: Database,
    tables: List<Table>,
    block: AgentBuilder.() -> Unit
): Agent {
    val builder = AgentBuilder().apply(block)

    val dialect = database.dialect.name
    val prompt = """
    You are an AI assistant that translates natural language questions into safe, parameterized SQL query components for a $dialect database.
    
    **Instructions:**
    1.  Analyze the user's question and the provided database schema.
    2.  Determine the appropriate SQL query structure and the specific parameter values derived from the user's request.
    3.  Generate a JSON object containing two keys:
        *   `"sql_template"`: A string containing the SQL query template. Use standard JDBC placeholders (`?`) for all user-provided values or literals derived from the request. Do **NOT** embed these values directly in the SQL string.
        *   `"parameters"`: A JSON array containing the values for the placeholders, in the exact order they appear in the `sql_template`. If no parameters are needed, provide an empty array `[]`.
    4.  Ensure the generated SQL is valid for $dialect and uses only the allowed tables/columns from the schema.
    5.  Prioritize efficiency (e.g., use indexed columns in WHERE clauses).
    6.  Output **only** the raw JSON object, without any surrounding text or markdown formatting.
    
    **Database Schema:**
    ```sql
        ${getDdlForTables(tables)}
    ```
    """
    builder.processor = processor@{ _, conversation ->
        flow {
            val options = LLMOptions(
                responseFormat = "json_object",
                systemPrompt = prompt,
                temperature = 0.7
            )
            val response = provider.generate(conversation.messages, options).toList().last { it is LLMMessage.AssistantMessage }
            val generatedSql = json.decodeFromString<GeneratedSql>((response as LLMMessage.AssistantMessage).content)

            emit(LLMMessage.AssistantMessage("Query Template: ${generatedSql.sqlTemplate}\nQuery Parameters: ${generatedSql.parameters}"))

//            val validation = validateAndAnalyzeSelectQuery(generatedSql.sqlTemplate, database, tables.map { it.tableName }.toSet())
//
//            if (!validation) {
//                emit(LLMMessage.SystemMessage("Generated query is not allowed!"))
//                throw SQLException("Validation failed")
//            }


            emit(runQuery(database, generatedSql))
        }

    }

    return builder.build()
}
