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
import com.zhenting.voicebridge.data.session.SessionStore

class AppContainer(val baseUrl: String) {
    private val sessionStore = SessionStore()
    private val api by lazy { RetrofitClient.create(baseUrl, sessionStore = sessionStore) }
    private val contentApi by lazy {
        // Chat may wait for OpenAI and then Google Sheets before returning.
        RetrofitClient.create(baseUrl, sessionStore = sessionStore, requestTimeoutSeconds = 180)
    }

    fun chatRepository(userId: String): ChatRepository = RemoteChatRepository(contentApi, sessionStore, userId)
    fun memoRepository(userId: String): MemoRepository = RemoteMemoRepository(contentApi, sessionStore, userId)
    fun speechRepository(userId: String): SpeechRepository = RemoteSpeechRepository(contentApi, sessionStore, userId)

    val authRepository: AuthRepository by lazy { RemoteAuthRepository(api, sessionStore) }

    val healthRepository: HealthRepository by lazy {
        RemoteHealthRepository(api)
    }
}
