package dev.gabrielolv.kaia.utils

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

@OptIn(ExperimentalSerializationApi::class)
actual val httpClient: HttpClient = HttpClient(CIO) {
    // Configure CIO engine if needed
    engine {
        // Example: Increase request timeout
        // requestTimeout = 60_000
    }

    // Add default Ktor client configurations here (e.g., Json serialization)

    // Add default Ktor client configurations here
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
            isLenient = true
            namingStrategy = JsonNamingStrategy.SnakeCase
        })
    }
    expectSuccess = true
}
