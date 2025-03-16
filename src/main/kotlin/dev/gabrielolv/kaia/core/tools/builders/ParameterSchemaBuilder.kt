package dev.gabrielolv.kaia.core.tools.builders

import kotlinx.serialization.json.*

/**
 * Builder for creating JSON Schema for tool parameters
 */
class ParameterSchemaBuilder {
    private val properties = mutableMapOf<String, JsonObject>()
    private val required = mutableListOf<String>()

    fun property(
        name: String,
        type: String,
        description: String,
        isRequired: Boolean = false,
        additionalProperties: Map<String, JsonElement> = emptyMap()
    ) {
        val property = buildJsonObject {
            put("type", type)
            put("description", description)
            for ((key, value) in additionalProperties) {
                put(key, value)
            }
        }

        properties[name] = property
        if (isRequired) {
            required.add(name)
        }
    }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for ((name, schema) in properties) {
                put(name, schema)
            }
        }
        putJsonArray("required") {
            required.forEach { add(it) }
        }
    }
}