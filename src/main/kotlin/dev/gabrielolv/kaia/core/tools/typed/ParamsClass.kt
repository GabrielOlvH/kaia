package dev.gabrielolv.kaia.core.tools.typed

import dev.gabrielolv.kaia.core.tools.typed.json.JsonUtils
import dev.gabrielolv.kaia.core.tools.typed.validation.*
import kotlinx.serialization.json.*
import kotlin.reflect.KClass

/**
 * Base class for parameter definitions, similar to Exposed's Table class
 */
abstract class ParamsClass {
    companion object {
        private val registry = mutableMapOf<KClass<out ParamsClass>, ParamsClass>()

        @Suppress("UNCHECKED_CAST")
        fun <T : ParamsClass> getInstance(clazz: KClass<T>): T {
            return registry.getOrPut(clazz) {
                clazz.objectInstance ?: error("${clazz.simpleName} must be an object")
            } as T
        }
    }

    private val properties = mutableListOf<Property<*>>()
    private val validations = mutableListOf<ValidationRule>()

    init {
        @Suppress("LeakingThis")
        registry[this::class] = this
    }

    protected fun <T> registerProperty(name: String, defaultValue: () -> T? = { null }): Property<T> {
        check(name.isNotBlank()) { "property name cannot be blank" }
        val property = Property(name, defaultValue)
        properties.add(property)
        return property
    }

    // Property registration methods
    fun string(name: String, default: String? = null) =
        registerProperty<String>(name) { default }.apply {
            type = String::class
        }

    fun int(name: String, default: Int? = null) =
        registerProperty<Int>(name) { default }.apply {
            type = Int::class
        }

    fun long(name: String, default: Long? = null) =
        registerProperty<Long>(name) { default }.apply {
            type = Long::class
        }

    fun float(name: String, default: Float? = null) =
        registerProperty<Float>(name) { default }.apply {
            type = Float::class
        }

    fun double(name: String, default: Double? = null) =
        registerProperty<Double>(name) { default }.apply {
            type = Double::class
        }

    fun boolean(name: String, default: Boolean? = null) =
        registerProperty<Boolean>(name) { default }.apply {
            type = Boolean::class
        }

    fun <T : Enum<T>> enum(name: String, enumClass: KClass<T>, default: T? = null) =
        registerProperty<T>(name) { default }.apply {
            type = enumClass
        }

    fun <T : ParamsClass> obj(name: String, paramsClass: KClass<T>, default: (() -> ParamsInstance)? = null) =
        registerProperty<ParamsInstance>(name) { default?.invoke() ?: ParamsInstance(paramsClass) }.apply {
            type = ParamsInstance::class
            this.isNestedObject = true
            this.paramsClass = paramsClass
        }

    fun <T : Any> list(name: String, itemClass: KClass<T>, default: List<T>? = null) =
        registerProperty<List<T>>(name) { default ?: emptyList() }.apply {
            type = List::class
            this.itemType = itemClass
            this.isList = true
        }

    fun <T : ParamsClass> objectList(
        name: String,
        paramsClass: KClass<T>,
        default: List<ParamsInstance>? = null
    ) = registerProperty<List<ParamsInstance>>(name) { default ?: emptyList() }.apply {
        type = List::class
        this.itemType = ParamsInstance::class
        this.isList = true
        this.isComplexList = true
        this.paramsClass = paramsClass
    }

    // Validation API
    fun required(vararg property: Property<*>) {
        property.forEach { validations.add(RequiredValidation(it)) }
    }

    fun <T : Comparable<T>> min(property: Property<T>, minValue: T) {
        validations.add(MinValueValidation(property, minValue))
    }

    fun <T : Comparable<T>> max(property: Property<T>, maxValue: T) {
        validations.add(MaxValueValidation(property, maxValue))
    }

    fun regex(property: Property<String>, pattern: Regex) {
        validations.add(RegexValidation(property, pattern))
    }

    fun custom(property: Property<*>, validation: (ParamsInstance) -> Boolean, message: String) {
        validations.add(CustomValidation(property, validation, message))
    }

    fun getProperties(): List<Property<*>> = properties

    fun getValidations(): List<ValidationRule> = validations

    fun getSchema(): JsonObject {
        val properties = mutableMapOf<String, JsonObject>()
        val required = mutableListOf<String>()

        this.properties.forEach { prop ->
            val propertySchema = JsonUtils.buildPropertySchema(prop, validations)
            properties[prop.name] = propertySchema

            // Check if property is required
            validations.forEach { validation ->
                if (validation is RequiredValidation && validation.getPropertyName() == prop.name) {
                    required.add(prop.name)
                }
            }
        }

        val schemaObject = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", JsonObject(properties))
            if (required.isNotEmpty()) {
                putJsonArray("required") {
                    required.forEach { add(it) }
                }
            }
        }

        return schemaObject
    }
}
