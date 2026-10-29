package com.zhenting.voicebridge.data.repository

import com.zhenting.voicebridge.data.model.MAX_AUDIO_BYTES
import com.zhenting.voicebridge.data.model.SpeechException
import com.zhenting.voicebridge.data.model.SpeechIssue
import com.zhenting.voicebridge.data.remote.ApiException
import com.zhenting.voicebridge.data.remote.ApiIssue
import com.zhenting.voicebridge.data.remote.VoiceBridgeApi
import com.zhenting.voicebridge.data.remote.apiCall
import com.zhenting.voicebridge.data.session.SessionChangedException
import com.zhenting.voicebridge.data.session.SessionStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

interface SpeechRepository {
    suspend fun transcribe(audio: File): String
}

class RemoteSpeechRepository(
    private val api: VoiceBridgeApi,
    private val sessions: SessionStore,
    private val userId: String
) : SpeechRepository {
    override suspend fun transcribe(audio: File): String {
        if (!audio.isFile || audio.length() == 0L) throw SpeechException(SpeechIssue.NoAudio)
        if (audio.length() > MAX_AUDIO_BYTES) throw SpeechException(SpeechIssue.TooLarge)
        val mediaType = when (audio.extension.lowercase()) {
            "m4a", "mp4" -> "audio/mp4"
            "mp3", "mpeg", "mpga" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "webm" -> "audio/webm"
            else -> throw SpeechException(SpeechIssue.NoAudio)
        }
        try {
            val credentials = sessions.credentials(userId)
            val part = MultipartBody.Part.createFormData(
                "file", audio.name, audio.asRequestBody(mediaType.toMediaType())
            )
            val response = apiCall { api.transcribe(part, credentials) }
            currentCoroutineContext().ensureActive()
            if (!sessions.matches(credentials)) throw SpeechException(SpeechIssue.SessionExpired)
            return response.text?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw SpeechException(SpeechIssue.NoSpeech)
        } catch (_: SessionChangedException) {
            throw SpeechException(SpeechIssue.SessionExpired)
        } catch (error: ApiException) {
            throw SpeechException(when (error.issue) {
                ApiIssue.Network -> SpeechIssue.Network
                ApiIssue.Timeout -> SpeechIssue.Timeout
                ApiIssue.SessionExpired -> SpeechIssue.SessionExpired
                else -> SpeechIssue.Unavailable
            })
        }
    }
}
