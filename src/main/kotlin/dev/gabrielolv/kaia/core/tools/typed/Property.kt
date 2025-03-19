package dev.gabrielolv.kaia.core.tools.typed

import kotlin.reflect.KClass

data class Property<T>(
    val name: String,
    val defaultValue: () -> T?,
    var type: KClass<*>? = null,
    var itemType: KClass<*>? = null,
    var isNestedObject: Boolean = false,
    var isList: Boolean = false,
    var isComplexList: Boolean = false,
    var toolParameters: KClass<out ToolParameters>? = null,
    var description: String? = null
) {
    fun withDescription(desc: String): Property<T> {
        description = desc
        return this
    }
}
