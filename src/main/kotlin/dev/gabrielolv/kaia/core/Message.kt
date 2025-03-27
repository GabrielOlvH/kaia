package dev.gabrielolv.kaia.core

import dev.gabrielolv.kaia.utils.nextMessageId
import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: String = nextMessageId,
    val conversationId: String = "",
    val sender: String = "",
    val recipient: String = "",
    val content: String,
    val metadata: Map<String, String> = emptyMap()
)