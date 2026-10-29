package com.zhenting.voicebridge.data.model

const val MAX_CHAT_CHARACTERS = 4000

fun chatCharacterCount(text: String): Int = text.codePointCount(0, text.length)

enum class MessageRole { User, Assistant }
enum class SheetSaveStatus { Saved, Unconfirmed }

data class ChatMessage(val id: Long, val role: MessageRole, val content: String, val createdAt: String)

data class Conversation(val id: String, val title: String, val createdAt: String, val updatedAt: String)

data class ConversationContent(
    val messages: List<ChatMessage>,
    val memoContext: List<MemoContext> = emptyList()
)

data class ChatTurn(
    val conversationId: String,
    val messages: List<ChatMessage>,
    val sheetStatus: SheetSaveStatus,
    val memoContext: List<MemoContext> = emptyList()
)

