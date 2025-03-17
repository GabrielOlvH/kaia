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
        additionalProperties: Map<String, JsonElement> = emptyMap(),
        nestedSchema: JsonObject? = null // New parameter for nested objects
    ) {
        val property = buildJsonObject {
            if (nestedSchema != null) {
                put("type", "object")
                put("properties", nestedSchema)
            } else {
                put("type", type)
            }
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

    fun parameters(name: String, description: String, isRequired: Boolean = false, block: ParameterSchemaBuilder.() -> Unit) {
        val nestedBuilder = ParameterSchemaBuilder()
        nestedBuilder.block()
        val nestedSchema = nestedBuilder.build()
        property(name, "object", description, isRequired, nestedSchema = nestedSchema)
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
