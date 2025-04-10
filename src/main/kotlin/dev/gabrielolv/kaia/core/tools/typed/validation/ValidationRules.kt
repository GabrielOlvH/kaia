package dev.gabrielolv.kaia.core.tools.typed.validation

import dev.gabrielolv.kaia.core.tools.typed.Property
import dev.gabrielolv.kaia.core.tools.typed.ToolParametersInstance

class RequiredValidation(private val property: Property<*>) : ValidationRule {
    override fun validate(instance: ToolParametersInstance): ValidationResult {
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

    fun getPropertyName(): String = property.name
}

class MinValueValidation<T : Comparable<T>>(
    private val property: Property<T>,
    private val minValue: T
) : ValidationRule {
    override fun validate(instance: ToolParametersInstance): ValidationResult {
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
}

class MaxValueValidation<T : Comparable<T>>(
    private val property: Property<T>,
    private val maxValue: T
) : ValidationRule {
    override fun validate(instance: ToolParametersInstance): ValidationResult {
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
}

class RegexValidation(
    private val property: Property<String>,
    private val pattern: Regex
) : ValidationRule {
    override fun validate(instance: ToolParametersInstance): ValidationResult {
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
}

class CustomValidation(
    private val name: String,
    private val validation: (ToolParametersInstance) -> Boolean,
    private val message: String
) : ValidationRule {
    override fun validate(instance: ToolParametersInstance): ValidationResult {
        return if (!validation(instance)) {
            ValidationResult.invalid(name, message)
        } else {
            ValidationResult.valid()
        }
    }
}
