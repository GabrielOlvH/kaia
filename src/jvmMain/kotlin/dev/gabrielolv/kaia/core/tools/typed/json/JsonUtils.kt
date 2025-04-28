package dev.gabrielolv.kaia.core.tools.typed.json

import kotlinx.serialization.json.*
import kotlin.reflect.KClass

actual fun JsonUtils.handleSpecialTypes(value: Any?): JsonElement {
    return when (value) {
        is Enum<*> -> JsonPrimitive(value.name)
        else -> JsonNull
    }
}

actual fun JsonUtils.handleSpecialPrimitive(element: JsonPrimitive, type: KClass<*>?): Any? {
    if (type?.java?.isEnum == true) {
        val enumValue = element.content
        val enumValues = type.java.enumConstants as? Array<Enum<*>>
        return enumValues?.find { it.name == enumValue }
    }
    return null
}

actual fun JsonUtils.handleEnumSchema(builder: JsonObjectBuilder, type: KClass<*>?) {
    if (type?.java?.isEnum == true) {
        builder.put("type", "string")
        val enumValues = type.java.enumConstants as? Array<Enum<*>>
        builder.putJsonArray("enum") {
            enumValues?.forEach { add(it.name) }
        }
    }
}
