package dev.gabrielolv.kaia.core.tools.typed

import dev.gabrielolv.kaia.core.tools.typed.json.JsonUtils
import dev.gabrielolv.kaia.core.tools.typed.validation.ValidationError
import dev.gabrielolv.kaia.core.tools.typed.validation.ValidationResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.reflect.KClass

/**
 * Represents an instance of parameters with values, similar to Exposed's Entity
 */
class ParamsInstance(private val paramsClass: KClass<out ParamsClass>) {
    private val values = mutableMapOf<Property<*>, Any?>()
    private val paramsClassInstance = ParamsClass.getInstance(paramsClass)

    /**
     * Get a property value using indexing operator
     */
    operator fun <T> get(property: Property<T>): T {
        @Suppress("UNCHECKED_CAST")
        return values[property] as? T ?: property.defaultValue() as T
    }

    /**
     * Set a property value using indexing operator
     */
    operator fun <T> set(property: Property<T>, value: T) {
        values[property] = value
    }

    /**
     * Check if a property has a value
     */
    fun has(property: Property<*>): Boolean {
        return values.containsKey(property)
    }

    /**
     * Get a property value or null
     */
    fun <T> getOrNull(property: Property<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return values[property] as? T ?: property.defaultValue()
    }

    /**
     * Validate all properties according to validation rules
     */
    fun validate(): ValidationResult {
        val errors = mutableListOf<ValidationError>()

        // Validate current level
        for (validation in paramsClassInstance.getValidations()) {
            val result = validation.validate(this)
            if (!result.isValid) {
                errors.addAll(result.errors)
            }
        }

        // Validate nested objects
        validateNestedObjects(errors)

        return if (errors.isEmpty()) {
            ValidationResult.valid()
        } else {
            ValidationResult(false, errors)
        }
    }

    private fun validateNestedObjects(errors: MutableList<ValidationError>) {
        paramsClassInstance.getProperties().forEach { prop ->
            when {
                // Validate single nested object
                prop.isNestedObject -> {
                    // Only validate if the property has a value
                    if (has(prop)) {
                        val nestedObj = getOrNull(prop) as? ParamsInstance
                        if (nestedObj != null) {
                            val nestedResult = nestedObj.validate()
                            if (!nestedResult.isValid) {
                                // Prefix property names with parent property name
                                val prefixedErrors = nestedResult.errors.map { error ->
                                    ValidationError("${prop.name}.${error.property}", error.message)
                                }
                                errors.addAll(prefixedErrors)
                            }
                        }
                    }
                }
                // Validate list of nested objects
                prop.isList && prop.isComplexList -> {
                    // Only validate if the property has a value
                    if (has(prop)) {
                        val list = getOrNull(prop) as? List<*> ?: emptyList<Any>()
                        list.forEachIndexed { index, item ->
                            if (item is ParamsInstance) {
                                val itemResult = item.validate()
                                if (!itemResult.isValid) {
                                    // Prefix property names with parent property name and index
                                    val prefixedErrors = itemResult.errors.map { error ->
                                        ValidationError(
                                            "${prop.name}[$index].${error.property}",
                                            error.message
                                        )
                                    }
                                    errors.addAll(prefixedErrors)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    /**
     * Parse from JsonObject
     */
    fun parseFromJson(json: JsonObject): ParamsInstance {
        paramsClassInstance.getProperties().forEach { prop ->
            val element = json[prop.name] ?: return@forEach
            val converted = JsonUtils.fromJsonElement(element, prop, this)
                values[prop] = converted
        }
        return this
    }

    /**
     * Parse from JSON string
     */
    fun parseFromJsonString(jsonString: String): ParamsInstance {
        val json = Json.parseToJsonElement(jsonString).jsonObject
        return parseFromJson(json)
    }

    /**
     * Convert to JsonObject
     */
    fun toJsonObject(): JsonObject {
        val map = mutableMapOf<String, JsonElement>()

        paramsClassInstance.getProperties().forEach { prop ->
            val value = getOrNull(prop)
            map[prop.name] = JsonUtils.toJsonElement(value)
        }

        return JsonObject(map)
    }

    /**
     * Convert to JSON string
     */
    fun toJsonString(): String {
        val json = Json {
            encodeDefaults = true
        }
        return json.encodeToString(JsonObject.serializer(), toJsonObject())
    }

    /**
     * Get the schema for this params instance
     */
    fun getSchema(): JsonObject {
        return paramsClassInstance.getSchema()
    }
}
