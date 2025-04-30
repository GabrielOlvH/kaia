package dev.gabrielolv.kaia.utils

import io.ktor.client.*
import io.ktor.client.engine.cio.*

actual val httpClient: HttpClient = HttpClient(CIO) {
    // Configure CIO engine if needed
    engine {
        // Example: Increase request timeout
        // requestTimeout = 60_000
    }

    // Add default Ktor client configurations here (e.g., Json serialization)
    // install(ContentNegotiation) {
    //     json()
    // }
}
