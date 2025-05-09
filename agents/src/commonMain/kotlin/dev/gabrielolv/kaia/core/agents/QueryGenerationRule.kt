package dev.gabrielolv.kaia.core.agents

/**
 * Defines the rules for how the DatabaseAgent should generate or select SQL queries.
 */
enum class QueryGenerationRule {
    /**
     * The LLM must select one of the existing predefined queries.
     * The agent expects a `PreDefinedQuerySelection` JSON object.
     */
    STRICT_PREDEFINED_ONLY,

    /**
     * The LLM can generate custom SQL.
     * If predefined queries are available, they are provided as context/reference to the LLM.
     * The agent expects a `GeneratedSql` JSON object.
     */
    ALLOW_CUSTOM_SQL,
}