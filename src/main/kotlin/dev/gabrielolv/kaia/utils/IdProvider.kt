package dev.gabrielolv.kaia.utils

import com.github.f4b6a3.ulid.UlidCreator

const val ulidSize = 26

const val threadIdLength = "thread-".length + ulidSize
val nextThreadId get() = generateId("thread")

const val messageIdLength = "message-".length + ulidSize
val nextMessageId get() = generateId("message")

const val toolCallsIdLength = "tool_calls-".length + ulidSize
val nextToolCallsId get() = generateId("tool_calls")

private fun generateId(prefix: String): String {
    val identifier = UlidCreator.getUlid()
    return "$prefix-$identifier"
}