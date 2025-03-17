package dev.gabrielolv.kaia.core.tools.typed

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer

/**
 * Custom annotation to provide description for schema properties
 */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
annotation class Desc(val value: String)

/**
 * Enhanced Schema Generator with description support
 */
object SchemaGenerator {
    /**
     * Generate JSON schema for a serializable class
     */
    inline fun <reified T : Any> generateSchema(): JsonObject {
        val descriptor = Json.serializersModule.serializer(T::class.java).descriptor
        return generateSchemaFromDescriptor(descriptor)
    }

    /**
     * Get description from annotations
     */

    private fun getDescription(descriptor: SerialDescriptor, index: Int = -1): String? {
        val annotations = if (index >= 0) {
            descriptor.getElementAnnotations(index)
        } else {
            descriptor.annotations
        }

        return annotations.filterIsInstance<Desc>().firstOrNull()?.value
    }

    /**
     * Generate schema from a serializer descriptor
     */
    fun generateSchemaFromDescriptor(descriptor: SerialDescriptor): JsonObject {
        val baseSchema = when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> generateObjectSchema(descriptor)
            PrimitiveKind.STRING -> JsonObject(mapOf("type" to JsonPrimitive("string")))
            PrimitiveKind.BOOLEAN -> JsonObject(mapOf("type" to JsonPrimitive("boolean")))
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG ->
                JsonObject(mapOf("type" to JsonPrimitive("integer")))
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE ->
                JsonObject(mapOf("type" to JsonPrimitive("number")))
            StructureKind.LIST -> generateArraySchema(descriptor)
            else -> JsonObject(mapOf("type" to JsonPrimitive("object")))
        }

        // Add class-level description if available
        val description = getDescription(descriptor)
        if (description != null) {
            return JsonObject(baseSchema + ("description" to JsonPrimitive(description)))
        }

        return baseSchema
    }

    /**
     * Generate schema for object types
     */
    private fun generateObjectSchema(descriptor: SerialDescriptor): JsonObject {
        val properties = buildJsonObject {
            for (i in 0 until descriptor.elementsCount) {
                val name = descriptor.getElementName(i)
                val childDescriptor = descriptor.getElementDescriptor(i)
                val childSchema = generateSchemaFromDescriptor(childDescriptor)

                // Add property description if available
                val description = getDescription(descriptor, i)
                if (description != null) {
                    put(name, JsonObject(childSchema + ("description" to JsonPrimitive(description))))
                } else {
                    put(name, childSchema)
                }
            }
        }

        // Collect required fields
        val required = buildJsonArray {
            for (i in 0 until descriptor.elementsCount) {
                if (!descriptor.isElementOptional(i)) {
                    add(JsonPrimitive(descriptor.getElementName(i)))
                }
            }
        }

        return buildJsonObject {
            put("type", "object")
            put("properties", properties)
            if (required.size > 0) {
                put("required", required)
            }
        }
    }

    /**
     * Generate schema for array types
     */
    private fun generateArraySchema(descriptor: SerialDescriptor): JsonObject {
        val itemDescriptor = descriptor.getElementDescriptor(0)
        return buildJsonObject {
            put("type", "array")
            put("items", generateSchemaFromDescriptor(itemDescriptor))
        }
    }
}