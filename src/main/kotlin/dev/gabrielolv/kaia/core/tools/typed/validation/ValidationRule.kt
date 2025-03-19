package dev.gabrielolv.kaia.core.tools.typed.validation

import dev.gabrielolv.kaia.core.tools.typed.ToolParametersInstance

interface ValidationRule {
    /**
     * Validate a property against this rule
     * @param instance The parameter instance containing values
     * @return ValidationResult indicating if validation passed
     */
    fun validate(instance: ToolParametersInstance): ValidationResult

    /**
     * Get the name of the property this rule validates
     */
    fun getPropertyName(): String
}
