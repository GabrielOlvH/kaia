package dev.gabrielolv.kaia.core.tools.typed

import kotlin.reflect.KClass

actual fun <T : ToolParameters> getToolParametersInstance(clazz: KClass<T>): T {
    return clazz.objectInstance ?: error("${clazz.simpleName} must be an object")
}
