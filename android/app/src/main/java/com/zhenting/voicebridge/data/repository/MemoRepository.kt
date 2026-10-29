package com.zhenting.voicebridge.data.repository

import com.zhenting.voicebridge.data.model.Memo
import com.zhenting.voicebridge.data.model.SheetSaveStatus
import com.zhenting.voicebridge.data.remote.ApiException
import com.zhenting.voicebridge.data.remote.ApiIssue
import com.zhenting.voicebridge.data.remote.MemoRequest
import com.zhenting.voicebridge.data.remote.MemoResponse
import com.zhenting.voicebridge.data.remote.VoiceBridgeApi
import com.zhenting.voicebridge.data.remote.apiCall
import com.zhenting.voicebridge.data.session.SessionChangedException
import com.zhenting.voicebridge.data.session.SessionStore
import com.zhenting.voicebridge.data.session.SessionCredentials
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

interface MemoRepository {
    suspend fun memos(): List<Memo>
    suspend fun save(prompt: String, requestId: String): Memo
}

class RemoteMemoRepository(
    private val api: VoiceBridgeApi,
    private val sessions: SessionStore,
    private val userId: String
) : MemoRepository {
    override suspend fun memos(): List<Memo> {
        val credentials = credentials()
        val result = apiCall { api.getMemos(credentials) }
        checkSession(credentials)
        val memos = result.memos?.map { parseMemo(it) } ?: invalid()
        if (memos.map { it.id }.distinct().size != memos.size) invalid()
        return memos
    }

    override suspend fun save(prompt: String, requestId: String): Memo {
        if (prompt.isBlank() || requestId.isBlank()) throw ApiException(ApiIssue.InvalidInput)
        val credentials = credentials()
        val result = apiCall(mutation = true) {
            api.saveMemo(MemoRequest(prompt.trim(), requestId), credentials)
        }
        checkSession(credentials)
        try {
            val memo = parseMemo(result.memo)
            if (result.saved != true || memo.prompt != prompt.trim()) invalid()
            return memo
        } catch (_: ApiException) {
            throw ApiException(ApiIssue.InvalidResponse, outcomeUnknown = true)
        }
    }

    private fun credentials() = try {
        sessions.credentials(userId)
    } catch (_: SessionChangedException) {
        throw ApiException(ApiIssue.SessionExpired)
    }

    private suspend fun checkSession(credentials: SessionCredentials) {
        currentCoroutineContext().ensureActive()
        if (!sessions.matches(credentials)) throw ApiException(ApiIssue.SessionExpired)
    }

    private fun parseMemo(item: MemoResponse?): Memo {
        val id = item?.id
        if (item == null || id == null || !Regex("[A-Za-z0-9_-]{1,128}").matches(id) ||
            item.prompt.isNullOrBlank() || item.createdAt.isNullOrBlank()) invalid()
        val status = when (item.sheetStatus) {
            "saved" -> SheetSaveStatus.Saved
            "pending", "unconfirmed" -> SheetSaveStatus.Unconfirmed
            else -> invalid()
        }
        return Memo(id, item.prompt, item.createdAt, status)
    }

    private fun invalid(): Nothing = throw ApiException(ApiIssue.InvalidResponse)
}
