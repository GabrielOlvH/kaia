package dev.gabrielolv.kaia.utils

import io.ktor.client.engine.*

expect fun createHttpEngine():  HttpClientEngineFactory<*>
