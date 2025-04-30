package dev.gabrielolv.kaia.utils

import io.ktor.client.* 
import io.ktor.client.engine.curl.*

actual val httpClient: HttpClient = HttpClient(Curl) {
    // Configure Curl engine if needed
    engine {
        // Example: configure SSL verification
        // sslVerify = true 
    }

    // Add default Ktor client configurations here
    // install(ContentNegotiation) {
    //     json()
    // }
}
