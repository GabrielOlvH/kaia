package dev.gabrielolv.kaia.utils

import ulid.ULID


const val ulidSize = 26

const val threadIdLength = "thread-".length + ulidSize
val nextThreadId get() = generateId("thread")

const val messageIdLength = "message-".length + ulidSize
val nextMessageId get() = generateId("message")

const val toolCallsIdLength = "tool_calls-".length + ulidSize
val nextToolCallsId get() = generateId("tool_calls")

private fun generateId(prefix: String): String {
    val identifier = ULID.nextULID()
    return "$prefix-$identifier"
}
