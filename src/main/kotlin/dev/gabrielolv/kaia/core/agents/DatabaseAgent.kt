package dev.gabrielolv.kaia.core.agents

import dev.gabrielolv.kaia.core.tools.createSqlTool
import dev.gabrielolv.kaia.core.tools.ToolManager
import dev.gabrielolv.kaia.llm.LLMOptions
import dev.gabrielolv.kaia.llm.LLMProvider
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction

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

fun Agent.Companion.withDatabaseAccess(
    provider: LLMProvider,
    toolManager: ToolManager,
    database: Database,
    tables: List<Table>,
    prompt: String = """
        You are a SQL expert that can easily translate natural language questions into safe, parameterized SQL query components for a ${database.dialect.name} database.
        **Instructions:**
        1.  Analyze the user's question and the provided database schema.
        2.  Determine the appropriate SQL query structure and the specific parameter values derived from the user's request.
        3.  Call the `sql_tool` tool with two arguments:
            *   `"sql_template"`: A string containing the SQL query template. Use standard JDBC placeholders (`?`) for all user-provided values or literals derived from the request. Do **NOT** embed these values directly in the SQL string.
            *   `"parameters"`: A JSON array containing the values for the placeholders, in the exact order they appear in the `sql_template`. If no parameters are needed, provide an empty array `[]`.
        4.  Ensure the generated SQL is valid for ${database.dialect.name} and uses only the allowed tables/columns from the schema.
        5.  Prioritize efficiency (e.g., use indexed columns in WHERE clauses).
        6.  Generate a readable message displaying the results from the query.
        
        **Database Schema:**
        ```sql
            ${getDdlForTables(tables)}
        ```
    """,

            block: AgentBuilder.() -> Unit
): Agent {
    val builder = AgentBuilder().apply(block)
    toolManager.registerTool(createSqlTool(database))

    builder.processor = processor@{ _, conversation ->
        val options = LLMOptions(
            systemPrompt = prompt,
            temperature = 0.7
        )

        return@processor provider.generate(conversation.messages, options)
    }

    return builder.build()
}
