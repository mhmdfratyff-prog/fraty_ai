package com.fraty.ai.history

data class ChatMessage(
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)
