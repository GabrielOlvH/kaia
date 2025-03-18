package dev.gabrielolv.kaia.core.tools.typed.validation

import kotlinx.serialization.Serializable

@Serializable
data class ValidationError(val property: String, val message: String)

data class ValidationResult(
    val isValid: Boolean,
    val errors: List<ValidationError> = emptyList()
) {
    companion object {
        fun valid() = ValidationResult(true)
        fun invalid(property: String, message: String) =
            ValidationResult(false, listOf(ValidationError(property, message)))
        fun combine(results: List<ValidationResult>): ValidationResult {
            val allErrors = results.flatMap { it.errors }
            return if (allErrors.isEmpty()) valid() else ValidationResult(false, allErrors)
        }
    }
}
