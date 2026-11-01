package com.zhenting.voicebridge.data.repository

import com.zhenting.voicebridge.data.model.ChatMessage
import com.zhenting.voicebridge.data.model.MemoAction
import com.zhenting.voicebridge.data.model.MemoSource
import com.zhenting.voicebridge.data.model.AnswerSection
import com.zhenting.voicebridge.data.model.AnswerKind
import com.zhenting.voicebridge.data.remote.AnswerSectionResponse
import com.zhenting.voicebridge.data.model.ChatTurn
import com.zhenting.voicebridge.data.model.Conversation
import com.zhenting.voicebridge.data.model.ConversationContent
import com.zhenting.voicebridge.data.model.MemoContext
import com.zhenting.voicebridge.data.model.MAX_CHAT_MEMOS
import com.zhenting.voicebridge.data.model.MAX_MEMO_CONTEXT_CHARACTERS
import com.zhenting.voicebridge.data.model.memoContextSize
import com.zhenting.voicebridge.data.model.MAX_CHAT_CHARACTERS
import com.zhenting.voicebridge.data.model.MessageRole
import com.zhenting.voicebridge.data.model.SheetSaveStatus
import com.zhenting.voicebridge.data.model.chatCharacterCount
import com.zhenting.voicebridge.data.remote.ApiException
import com.zhenting.voicebridge.data.remote.ApiIssue
import com.zhenting.voicebridge.data.remote.ChatRequest
import com.zhenting.voicebridge.data.remote.MessageResponse
import com.zhenting.voicebridge.data.remote.MemoContextResponse
import com.zhenting.voicebridge.data.remote.VoiceBridgeApi
import com.zhenting.voicebridge.data.remote.apiCall
import com.zhenting.voicebridge.data.session.SessionChangedException
import com.zhenting.voicebridge.data.session.SessionStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

interface ChatRepository {
    suspend fun conversations(): List<Conversation>
    suspend fun messages(conversationId: String): ConversationContent
    suspend fun send(message: String, conversationId: String?, memoIds: List<String> = emptyList(), memoAction: MemoAction? = null): ChatTurn
}

class RemoteChatRepository(
    private val api: VoiceBridgeApi,
    private val sessions: SessionStore,
    private val userId: String
) : ChatRepository {
    override suspend fun conversations(): List<Conversation> {
        val credentials = credentials()
        val response = apiCall { api.getConversations(credentials) }
        checkSession(credentials)
        val result = response.conversations?.map { item ->
            if (item == null) invalid()
            Conversation(validId(item.id), required(item.title), required(item.createdAt), required(item.updatedAt))
        } ?: invalid()
        if (result.map { it.id }.distinct().size != result.size) invalid()
        return result
    }

    override suspend fun messages(conversationId: String): ConversationContent {
        validId(conversationId)
        val credentials = credentials()
        val response = apiCall { api.getMessages(conversationId, credentials) }
        checkSession(credentials)
        if (response.conversationId != conversationId) invalid()
        val context = parseMemoContext(response.memoContext)
        return ConversationContent(parseMessages(response.messages, context), context)
    }

    override suspend fun send(message: String, conversationId: String?, memoIds: List<String>, memoAction: MemoAction?): ChatTurn {
        val prompt = message.trim()
        if (prompt.isEmpty() || chatCharacterCount(prompt) > MAX_CHAT_CHARACTERS) {
            throw ApiException(ApiIssue.InvalidInput)
        }
        if (conversationId != null) validId(conversationId)
        if (memoIds.size > MAX_CHAT_MEMOS || memoIds.distinct().size != memoIds.size ||
            (conversationId != null && memoIds.isNotEmpty())) throw ApiException(ApiIssue.InvalidInput)
        memoIds.forEach { validId(it) }
        if (memoAction != null && (conversationId != null || memoIds.isEmpty())) throw ApiException(ApiIssue.InvalidInput)
        val credentials = credentials()
        val response = apiCall(mutation = true) {
            api.sendMessage(ChatRequest(prompt, conversationId, memoIds.takeIf { it.isNotEmpty() }, memoAction?.apiValue), credentials)
        }
        checkSession(credentials)
        try {
            val id = validId(response.conversationId)
            if (conversationId != null && id != conversationId) invalid()
            val memoContext = parseMemoContext(response.memoContext)
            val messages = parseMessages(response.messages, memoContext)
            if (memoIds.isNotEmpty() && memoContext.map { it.id } != memoIds) invalid()
            if (messages.size != 2 || messages[0].role != MessageRole.User ||
                messages[1].role != MessageRole.Assistant || messages[0].content != prompt ||
                messages[1].content != response.reply
            ) invalid()
            val sheet = response.sheet
            val status = if (sheet?.saved == true && sheet.updatedRows == 1 &&
                !sheet.updatedRange.isNullOrBlank()) SheetSaveStatus.Saved else SheetSaveStatus.Unconfirmed
            return ChatTurn(id, messages, status, memoContext)
        } catch (_: ApiException) {
            throw ApiException(ApiIssue.InvalidResponse, outcomeUnknown = true)
        }
    }

    private fun credentials() = try {
        sessions.credentials(userId)
    } catch (_: SessionChangedException) {
        throw ApiException(ApiIssue.SessionExpired)
    }

    private suspend fun checkSession(credentials: com.zhenting.voicebridge.data.session.SessionCredentials) {
        currentCoroutineContext().ensureActive()
        if (!sessions.matches(credentials)) throw ApiException(ApiIssue.SessionExpired)
    }

    private fun parseMessages(items: List<MessageResponse?>?, context: List<MemoContext>): List<ChatMessage> {
        val result = items?.map { item ->
            if (item == null || item.id == null || item.id <= 0) invalid()
            val role = when (item.role) {
                "user" -> MessageRole.User
                "assistant" -> MessageRole.Assistant
                else -> invalid()
            }
            val content = required(item.content)
            val sections = parseSections(item.sections, context)
            if (sections.isNotEmpty() && (role != MessageRole.Assistant || sections.joinToString("\n\n") { it.text } != content)) invalid()
            ChatMessage(item.id, role, content, required(item.createdAt), sections)
        } ?: invalid()
        if (result.map { it.id }.distinct().size != result.size) invalid()
        return result
    }

    private fun parseSections(items: List<AnswerSectionResponse?>?, context: List<MemoContext>): List<AnswerSection> {
        if (items == null || items.isEmpty()) return emptyList()
        if (items.size > 16 || context.isEmpty()) invalid()
        return items.map { item ->
            if (item == null) invalid()
            val text = required(item.text)
            if (chatCharacterCount(text) > 6000) invalid()
            val kind = when (item.kind) {
                "note" -> AnswerKind.Note
                "general" -> AnswerKind.General
                "not_found" -> AnswerKind.NotFound
                else -> invalid()
            }
            val sources = item.sources?.map { source ->
                if (source == null) invalid()
                val memo = context.find { it.id == source.memoId } ?: invalid()
                val paragraphId = required(source.paragraphId)
                val quote = required(source.quote)
                if (!Regex("p[1-9][0-9]*").matches(paragraphId) || chatCharacterCount(quote) > 1000 ||
                    !memo.prompt.contains(quote) || source.memoTitle != memo.displayTitle) invalid()
                MemoSource(memo.id, memo.displayTitle, paragraphId, quote)
            } ?: invalid()
            if (sources.size > 4 || sources.distinctBy { it.memoId to it.paragraphId }.size != sources.size ||
                (kind == AnswerKind.Note && sources.isEmpty()) || (kind != AnswerKind.Note && sources.isNotEmpty())) invalid()
            AnswerSection(text, kind, sources)
        }
    }

    private fun parseMemoContext(items: List<MemoContextResponse?>?): List<MemoContext> {
        if (items == null) return emptyList()
        if (items.size > MAX_CHAT_MEMOS) invalid()
        val result = items.map { item ->
            if (item == null) invalid()
            if (item.title != null && (item.title.isBlank() || chatCharacterCount(item.title) > 120)) invalid()
            MemoContext(validId(item.id), required(item.prompt), required(item.createdAt), item.title)
        }
        if (result.map { it.id }.distinct().size != result.size ||
            memoContextSize(result) > MAX_MEMO_CONTEXT_CHARACTERS) invalid()
        return result
    }

    private fun validId(id: String?): String {
        if (id == null || !Regex("[A-Za-z0-9_-]{1,128}").matches(id)) invalid()
        return id
    }

    private fun required(value: String?): String = value?.takeIf { it.isNotBlank() } ?: invalid()
    private fun invalid(): Nothing = throw ApiException(ApiIssue.InvalidResponse)
}
