package dev.gabrielolv.kaia.core.tools.typed.validation

import dev.gabrielolv.kaia.core.tools.typed.ParamsInstance
import dev.gabrielolv.kaia.core.tools.typed.Property

class RequiredValidation(private val property: Property<*>) : ValidationRule {
    override fun validate(instance: ParamsInstance): ValidationResult {
        // Check if the property has a value set
        if (!instance.has(property)) {
            return ValidationResult.invalid(property.name, "is required")
        }

        // Check if the value is null
        val value = instance.getOrNull(property)
        return if (value == null) {
            ValidationResult.invalid(property.name, "is required")
        } else {
            ValidationResult.valid()
        }
    }

    override fun getPropertyName(): String = property.name
}

class MinValueValidation<T : Comparable<T>>(
    private val property: Property<T>,
    private val minValue: T
) : ValidationRule {
    override fun validate(instance: ParamsInstance): ValidationResult {
        // Skip validation if property is not set
        if (!instance.has(property)) {
            return ValidationResult.valid()
        }

        val value = instance.getOrNull(property) ?: return ValidationResult.valid()
        return if (value < minValue) {
            ValidationResult.invalid(property.name, "must be at least $minValue")
        } else {
            ValidationResult.valid()
        }
    }

    override fun getPropertyName(): String = property.name

    fun getMinValue(): T = minValue
}

class MaxValueValidation<T : Comparable<T>>(
    private val property: Property<T>,
    private val maxValue: T
) : ValidationRule {
    override fun validate(instance: ParamsInstance): ValidationResult {
        // Skip validation if property is not set
        if (!instance.has(property)) {
            return ValidationResult.valid()
        }

        val value = instance.getOrNull(property) ?: return ValidationResult.valid()
        return if (value > maxValue) {
            ValidationResult.invalid(property.name, "must be at most $maxValue")
        } else {
            ValidationResult.valid()
        }
    }

    override fun getPropertyName(): String = property.name

    fun getMaxValue(): T = maxValue
}

class RegexValidation(
    private val property: Property<String>,
    private val pattern: Regex
) : ValidationRule {
    override fun validate(instance: ParamsInstance): ValidationResult {
        // Skip validation if property is not set
        if (!instance.has(property)) {
            return ValidationResult.valid()
        }

        val value = instance.getOrNull(property) ?: return ValidationResult.valid()
        return if (!pattern.matches(value)) {
            ValidationResult.invalid(property.name, "does not match required pattern")
        } else {
            ValidationResult.valid()
        }
    }

    override fun getPropertyName(): String = property.name

    fun getPattern(): Regex = pattern
}

class CustomValidation(
    private val property: Property<*>,
    private val validation: (ParamsInstance) -> Boolean,
    private val message: String
) : ValidationRule {
    override fun validate(instance: ParamsInstance): ValidationResult {
        // Skip validation if property is not set
        if (!instance.has(property)) {
            return ValidationResult.valid()
        }

        return if (!validation(instance)) {
            ValidationResult.invalid(property.name, message)
        } else {
            ValidationResult.valid()
        }
    }

    override fun getPropertyName(): String = property.name
}
