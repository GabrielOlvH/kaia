package dev.gabrielolv.kaia.utils

import io.ktor.client.engine.*
import io.ktor.client.engine.curl.*

actual fun createHttpEngine(): HttpClientEngineFactory<*> = Curl