package dev.gabrielolv.kaia.core.tools.typed.json

import dev.gabrielolv.kaia.core.tools.typed.ParamsClass
import dev.gabrielolv.kaia.core.tools.typed.ParamsInstance
import dev.gabrielolv.kaia.core.tools.typed.Property
import dev.gabrielolv.kaia.core.tools.typed.validation.MaxValueValidation
import dev.gabrielolv.kaia.core.tools.typed.validation.MinValueValidation
import dev.gabrielolv.kaia.core.tools.typed.validation.RegexValidation
import dev.gabrielolv.kaia.core.tools.typed.validation.ValidationRule
import kotlinx.serialization.json.*
import kotlin.reflect.KClass

object JsonUtils {
    /**
     * Converts a primitive value to a JsonElement
     */
    fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Enum<*> -> JsonPrimitive(value.name)
        is ParamsInstance -> value.toJsonObject()
        is List<*> -> JsonArray(value.map { toJsonElement(it) })
        else -> JsonNull
    }

    /**
     * Converts a JsonElement to a typed value based on the property type
     */
    fun <T> fromJsonElement(
        element: JsonElement,
        prop: Property<T>,
        parentInstance: ParamsInstance? = null
    ): Any? {
        return when {
            element is JsonNull -> null
            prop.isNestedObject && element is JsonObject -> {
                val nestedClass = prop.paramsClass ?: return null
                val nestedInstance = ParamsInstance(nestedClass)
                nestedInstance.parseFromJson(element)
                nestedInstance
            }
            prop.isList && element is JsonArray -> {
                if (prop.isComplexList) {
                    parseComplexList(element, prop).let { it.ifEmpty { null } }
                } else {
                    parsePrimitiveList(element, prop).let { it.ifEmpty { null } }
                }
            }
            else -> {
                parsePrimitive(element, prop.type)
            }
        }
    }

    private fun parseComplexList(element: JsonArray, prop: Property<*>): List<Any> {
        val nestedClass = prop.paramsClass ?: return emptyList()

        return element.mapNotNull { item ->
            if (item is JsonObject) {
                val nestedInstance = ParamsInstance(nestedClass)
                nestedInstance.parseFromJson(item)
                nestedInstance
            } else {
                null
            }
        }
    }

    private fun parsePrimitiveList(element: JsonArray, prop: Property<*>): List<Any?> {
        return element.mapNotNull { item ->
            parsePrimitive(item, prop.itemType)
        }
    }

    private fun parsePrimitive(element: JsonElement, type: KClass<*>?): Any? {
        if (element !is JsonPrimitive) return null

        return when (type) {
            String::class -> element.contentOrNull
            Int::class -> element.intOrNull
            Long::class -> element.longOrNull
            Double::class -> element.doubleOrNull
            Float::class -> element.floatOrNull
            Boolean::class -> element.booleanOrNull
            else -> {
                if (type?.java?.isEnum == true) {
                    val enumValue = element.content
                    val enumValues = type.java.enumConstants as? Array<Enum<*>>
                    enumValues?.find { it.name == enumValue }
                } else {
                    null
                }
            }
        }
    }

    /**
     * Builds a JSON schema for a property
     */
    fun buildPropertySchema(prop: Property<*>, validations: List<ValidationRule>): JsonObject {
        return buildJsonObject {
            when {
                prop.isNestedObject -> buildNestedObjectSchema(prop)
                prop.isList -> buildListSchema(prop)
                else -> buildPrimitiveSchema(prop)
            }

            // Add validation constraints to schema
            addValidationConstraints(prop, validations)

            // Add description if available
            prop.description?.let {
                put("description", JsonPrimitive(it))
            }
        }
    }

    private fun JsonObjectBuilder.buildNestedObjectSchema(prop: Property<*>) {
        put("type", "object")
        val nestedClass = prop.paramsClass
        if (nestedClass != null) {
            val nestedInstance = ParamsClass.getInstance(nestedClass)
            val nestedSchema = nestedInstance.getSchema()
            put("properties", nestedSchema["properties"] ?: JsonObject(emptyMap()))
            if (nestedSchema.containsKey("required")) {
                put("required", nestedSchema["required"] ?: JsonArray(emptyList()))
            }
        }
    }

    private fun JsonObjectBuilder.buildListSchema(prop: Property<*>) {
        put("type", "array")
        put("items", buildJsonObject {
            if (prop.isComplexList) {
                // Complex object items
                put("type", "object")
                val nestedClass = prop.paramsClass
                if (nestedClass != null) {
                    val nestedInstance = ParamsClass.getInstance(nestedClass)
                    val nestedSchema = nestedInstance.getSchema()
                    put("properties", nestedSchema["properties"] ?: JsonObject(emptyMap()))
                    if (nestedSchema.containsKey("required")) {
                        put("required", nestedSchema["required"] ?: JsonArray(emptyList()))
                    }
                }
            } else {
                // Primitive items
                when (prop.itemType) {
                    String::class -> put("type", "string")
                    Int::class, Long::class -> put("type", "integer")
                    Float::class, Double::class -> put("type", "number")
                    Boolean::class -> put("type", "boolean")
                    else -> {
                        if (prop.itemType?.java?.isEnum == true) {
                            put("type", "string")
                            val enumValues = prop.itemType?.java?.enumConstants as? Array<Enum<*>>
                            putJsonArray("enum") {
                                enumValues?.forEach { add(it.name) }
                            }
                        }
                    }
                }
            }
        })
    }

    private fun JsonObjectBuilder.buildPrimitiveSchema(prop: Property<*>) {
        when (prop.type) {
            String::class -> put("type", "string")
            Int::class, Long::class -> put("type", "integer")
            Float::class, Double::class -> put("type", "number")
            Boolean::class -> put("type", "boolean")
            else -> {
                if (prop.type?.java?.isEnum == true) {
                    put("type", "string")
                    val enumValues = prop.type?.java?.enumConstants as? Array<Enum<*>>
                    putJsonArray("enum") {
                        enumValues?.forEach { add(it.name) }
                    }
                }
            }
        }
    }

    private fun JsonObjectBuilder.addValidationConstraints(
        prop: Property<*>,
        validations: List<ValidationRule>
    ) {
        validations.forEach { validation ->
            if (validation.getPropertyName() == prop.name) {
                when (validation) {
                    is MinValueValidation<*> -> {
                        put(
                            "minimum",
                            JsonPrimitive(validation.getMinValue().toString().toDoubleOrNull() ?: 0.0)
                        )
                    }
                    is MaxValueValidation<*> -> {
                        put(
                            "maximum",
                            JsonPrimitive(validation.getMaxValue().toString().toDoubleOrNull() ?: 0.0)
                        )
                    }
                    is RegexValidation -> {
                        put("pattern", JsonPrimitive(validation.getPattern().pattern))
                    }
                    // Other validation types can be added here
                }
            }
        }
    }
}
