package com.zhenting.voicebridge.ui.chat

import com.zhenting.voicebridge.data.model.ChatMessage
import com.zhenting.voicebridge.data.model.Conversation
import com.zhenting.voicebridge.data.model.MemoContext
import com.zhenting.voicebridge.data.model.MemoAction
import com.zhenting.voicebridge.data.model.SheetSaveStatus
import com.zhenting.voicebridge.data.remote.ApiIssue

data class ChatUiState(
    val draft: String = "",
    val conversationId: String? = null,
    val title: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val memoContext: List<MemoContext> = emptyList(),
    val conversations: List<Conversation> = emptyList(),
    val isLoadingConversations: Boolean = false,
    val isLoadingMessages: Boolean = false,
    val isSending: Boolean = false,
    val sheetStatus: SheetSaveStatus? = null,
    val error: ApiIssue? = null,
    val historyError: ApiIssue? = null,
    val requiresReload: Boolean = false,
    val memoAction: MemoAction? = null
)
