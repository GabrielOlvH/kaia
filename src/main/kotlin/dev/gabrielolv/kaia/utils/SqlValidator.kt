package dev.gabrielolv.kaia.utils

import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.util.TablesNamesFinder
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.SQLiteDialect
import java.sql.SQLException

class SqlValidationException(message: String) : RuntimeException(message)

fun validateAndAnalyzeSelectQuery(
    sql: String,
    db: Database,
    allowedTables: Set<String>
): Boolean {
    if (!validateSelectStructure(sql, allowedTables)) {
        return false
    }

    if (!explainAndAnalyzePlan(sql, db)) {
        return false
    }

    return true
}
/**
 * Performs basic structural validation on a SQL string to ensure it's a
 * single SELECT statement and optionally checks allowed tables.
 */
fun validateSelectStructure(sql: String, allowedTables: Set<String>): Boolean {
    try {
        val statement = CCJSqlParserUtil.parse(sql) // Use parse() for single statement

        // 1. Check statement type
        if (statement !is Select) {
            throw SqlValidationException(
                "Validation failed: Statement type " +
                        "${statement::class.java.simpleName} is not SELECT."
            )
        }

        // 2. Optional: Check tables used (using TablesNamesFinder)
        val tablesNamesFinder = TablesNamesFinder()
        val tables = tablesNamesFinder.getTables(statement as Statement)
        for (tableName in tables) {
            val cleanTableName =
                tableName.lowercase().removeSurrounding("`").removeSurrounding("\"")
            if (cleanTableName !in allowedTables) {
                throw SqlValidationException(
                    "Validation failed: Table '$tableName' is not allowed in SELECT statements."
                )
            }
        }
        return true

    } catch (e: JSQLParserException) {
        System.err.println("Parsing failed: ${e.message}")
        return false // Syntactically incorrect SQL
    } catch (e: SqlValidationException) {
        System.err.println(e.message)
        return false // Failed custom validation
    } catch (e: Exception) {
        System.err.println("An unexpected error occurred during JSqlParser validation: ${e.message}")
        e.printStackTrace()
        return false
    }
}

const val MAX_ESTIMATED_COST_THRESHOLD = 10000.0
/**
 * Executes EXPLAIN on the given SQL query and performs basic analysis
 * on the query plan (e.g., checks for Seq Scans on large tables, high cost).
 *
 * @param sql The SELECT query to analyze.
 * @param db An active JDBC connection.
 * @return True if the plan seems acceptable, false otherwise.
 */
fun explainAndAnalyzePlan(
    sql: String,
    db: Database,
): Boolean {
    val dialect = db.dialect
    val explainSql = when (dialect) {
        is SQLiteDialect -> "EXPLAIN QUERY PLAN $sql"
        else -> "EXPLAIN $sql"
    }

    println("Executing EXPLAIN: $explainSql")

    return transaction(db) {
        try {
            exec(explainSql, explicitStatementType = StatementType.SELECT) { resultSet ->

                var estimatedCost = 0.0

                while (resultSet.next()) {
                    val planLine = resultSet.getString(1)

                    val costMatch = """cost=\d+\.\d+\.\.(\d+\.\d+)""".toRegex().find(planLine)
                    if (costMatch != null) {
                        val cost = costMatch.groupValues[1].toDoubleOrNull()
                        if (cost != null) {
                            estimatedCost = maxOf(estimatedCost, cost)
                        }
                    }
                }
                if (estimatedCost > MAX_ESTIMATED_COST_THRESHOLD) {
                    System.err.println(
                        "Validation failed: Estimated cost ($estimatedCost) " +
                                "exceeds threshold ($MAX_ESTIMATED_COST_THRESHOLD)."
                    )
                    return@exec false // Failed due to high cost
                }

                return@exec true // Plan seems acceptable based on basic checks
            }

        } catch (e: SQLException) {
            System.err.println("EXPLAIN execution failed: ${e.message}")
            return@transaction false
        } catch (e: Exception) {
            System.err.println("An unexpected error occurred during EXPLAIN analysis: ${e.message}")
            e.printStackTrace()
            return@transaction false
        }
    } ?: false
}

