package dev.gabrielolv.kaia.examples

/**
 * Actual implementation of getEnv for the JVM platform.
 */
actual fun getEnv(name: String): String? = System.getenv(name)
