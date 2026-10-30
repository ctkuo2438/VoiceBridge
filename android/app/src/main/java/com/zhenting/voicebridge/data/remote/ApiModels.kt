package com.zhenting.voicebridge.data.remote

import com.google.gson.annotations.SerializedName

// Responses are untrusted input, so the repository validates nullable fields.
data class HealthResponse(val status: String? = null)

data class GoogleChallengeResponse(
    @SerializedName("client_id") val clientId: String? = null,
    val nonce: String? = null,
    @SerializedName("expires_in") val expiresIn: Long? = null
)

data class GoogleLoginRequest(
    @SerializedName("id_token") val idToken: String,
    val nonce: String
)

data class GoogleLoginResponse(
    @SerializedName("access_token") val accessToken: String? = null,
    @SerializedName("token_type") val tokenType: String? = null,
    @SerializedName("expires_in") val expiresIn: Long? = null,
    val user: UserResponse? = null
)

data class UserResponse(
    val id: String? = null,
    val name: String? = null,
    val email: String? = null,
    @SerializedName("avatar_url") val avatarUrl: String? = null
)

data class CurrentUserResponse(val user: UserResponse? = null)

data class LogoutResponse(val message: String? = null)

data class ApiErrorResponse(val code: String? = null)

data class ChatRequest(
    val message: String,
    @SerializedName("conversation_id") val conversationId: String? = null,
    @SerializedName("memo_ids") val memoIds: List<String>? = null
)

data class MessageResponse(
    val id: Long? = null,
    val role: String? = null,
    val content: String? = null,
    @SerializedName("created_at") val createdAt: String? = null
)

data class ConversationResponse(
    val id: String? = null,
    val title: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null
)

data class ConversationsResponse(val conversations: List<ConversationResponse?>? = null)

data class ConversationMessagesResponse(
    @SerializedName("conversation_id") val conversationId: String? = null,
    val messages: List<MessageResponse?>? = null,
    @SerializedName("memo_context") val memoContext: List<MemoContextResponse?>? = null
)

data class SheetResponse(
    val saved: Boolean? = null,
    val timestamp: String? = null,
    @SerializedName("updated_range") val updatedRange: String? = null,
    @SerializedName("updated_rows") val updatedRows: Int? = null
)

data class ChatResponse(
    @SerializedName("conversation_id") val conversationId: String? = null,
    val reply: String? = null,
    val messages: List<MessageResponse?>? = null,
    val sheet: SheetResponse? = null,
    @SerializedName("memo_context") val memoContext: List<MemoContextResponse?>? = null
)

data class MemoContextResponse(
    val id: String? = null,
    val prompt: String? = null,
    @SerializedName("created_at") val createdAt: String? = null
)

data class MemoRequest(
    val prompt: String,
    @SerializedName("request_id") val requestId: String
)

data class MemoResponse(
    val id: String? = null,
    val prompt: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("sheet_status") val sheetStatus: String? = null
)

data class MemosResponse(val memos: List<MemoResponse?>? = null)

data class MemoSaveResponse(
    val saved: Boolean? = null,
    val memo: MemoResponse? = null,
    val sheet: SheetResponse? = null
)

data class TranscriptionResponse(val text: String? = null)

data class TtsRequest(val text: String)
