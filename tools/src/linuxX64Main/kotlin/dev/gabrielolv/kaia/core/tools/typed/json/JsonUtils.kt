package dev.gabrielolv.kaia.core.tools.typed.json

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlin.reflect.KClass

actual fun JsonUtils.handleSpecialTypes(value: Any?): JsonElement = JsonNull

actual fun JsonUtils.handleSpecialPrimitive(element: JsonPrimitive, type: KClass<*>?): Any? = null

actual fun JsonUtils.handleEnumSchema(builder: JsonObjectBuilder, type: KClass<*>?) {
    // No enum support on non-JVM platforms
}
