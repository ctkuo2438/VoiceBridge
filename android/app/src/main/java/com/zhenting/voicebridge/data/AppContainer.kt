package com.zhenting.voicebridge.data

import com.zhenting.voicebridge.data.remote.RetrofitClient
import com.zhenting.voicebridge.data.repository.AuthRepository
import com.zhenting.voicebridge.data.repository.HealthRepository
import com.zhenting.voicebridge.data.repository.RemoteAuthRepository
import com.zhenting.voicebridge.data.repository.RemoteHealthRepository
import com.zhenting.voicebridge.data.repository.ChatRepository
import com.zhenting.voicebridge.data.repository.MemoRepository
import com.zhenting.voicebridge.data.repository.RemoteChatRepository
import com.zhenting.voicebridge.data.repository.RemoteMemoRepository
import com.zhenting.voicebridge.data.repository.SpeechRepository
import com.zhenting.voicebridge.data.repository.RemoteSpeechRepository
import com.zhenting.voicebridge.data.repository.TtsRepository
import com.zhenting.voicebridge.data.repository.RemoteTtsRepository
import com.zhenting.voicebridge.data.session.SessionStore
import com.zhenting.voicebridge.data.local.PersistentRecoveryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.io.File
import java.security.MessageDigest

class AppContainer(val baseUrl: String, private val recoveryRoot: File) {
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recoveries = mutableMapOf<String, Deferred<PersistentRecoveryStore>>()

    fun recoveryDirectory(userId: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(recoveryRoot, key)
    }

    suspend fun recoveryStore(userId: String): PersistentRecoveryStore {
        val pending = synchronized(recoveries) {
            recoveries.getOrPut(userId) {
                recoveryScope.async { PersistentRecoveryStore.open(recoveryDirectory(userId), recoveryScope) }
            }
        }
        try {
            return pending.await()
        } catch (error: Exception) {
            synchronized(recoveries) { if (recoveries[userId] === pending && pending.isCompleted) recoveries.remove(userId) }
            throw error
        }
    }
    private val sessionStore = SessionStore()
    private val api by lazy { RetrofitClient.create(baseUrl, sessionStore = sessionStore) }
    private val contentApi by lazy {
        // Chat may wait for OpenAI and then Google Sheets before returning.
        RetrofitClient.create(baseUrl, sessionStore = sessionStore, requestTimeoutSeconds = 180)
    }

    fun chatRepository(userId: String): ChatRepository = RemoteChatRepository(contentApi, sessionStore, userId)
    fun memoRepository(userId: String): MemoRepository = RemoteMemoRepository(contentApi, sessionStore, userId)
    fun speechRepository(userId: String): SpeechRepository = RemoteSpeechRepository(contentApi, sessionStore, userId)
    fun ttsRepository(userId: String): TtsRepository = RemoteTtsRepository(contentApi, sessionStore, userId)

    val authRepository: AuthRepository by lazy { RemoteAuthRepository(api, sessionStore) }

    val healthRepository: HealthRepository by lazy {
        RemoteHealthRepository(api)
    }
}
