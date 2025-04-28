package dev.gabrielolv.kaia.core.tools.typed

import kotlin.reflect.KClass

actual fun <T : ToolParameters> getToolParametersInstance(clazz: KClass<T>): T {
    error("${clazz.simpleName} must be registered manually using ToolParameters.register() on non-JVM platforms")
}
