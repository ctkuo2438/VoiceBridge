package com.zhenting.voicebridge.data.repository

import com.zhenting.voicebridge.data.model.MAX_TTS_AUDIO_BYTES
import com.zhenting.voicebridge.data.model.MAX_TTS_CHARACTERS
import com.zhenting.voicebridge.data.model.TtsException
import com.zhenting.voicebridge.data.model.TtsIssue
import com.zhenting.voicebridge.data.model.chatCharacterCount
import com.zhenting.voicebridge.data.remote.ApiException
import com.zhenting.voicebridge.data.remote.ApiIssue
import com.zhenting.voicebridge.data.remote.TtsRequest
import com.zhenting.voicebridge.data.remote.VoiceBridgeApi
import com.zhenting.voicebridge.data.remote.apiCall
import com.zhenting.voicebridge.data.session.SessionStore
import com.zhenting.voicebridge.data.session.SessionChangedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException

interface TtsRepository {
    suspend fun synthesize(text: String): ByteArray
}

class RemoteTtsRepository(
    private val api: VoiceBridgeApi,
    private val sessions: SessionStore,
    private val userId: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : TtsRepository {
    override suspend fun synthesize(text: String): ByteArray {
        val input = text.trim()
        if (input.isEmpty() || chatCharacterCount(input) > MAX_TTS_CHARACTERS) {
            throw TtsException(TtsIssue.InvalidText)
        }
        try {
            val credentials = sessions.credentials(userId)
            val body = apiCall {
                val response = api.createSpeech(TtsRequest(input), credentials)
                if (response.code() == 429) {
                    response.errorBody()?.close()
                    throw TtsException(TtsIssue.RateLimited)
                }
                response
            }
            return coroutineScope {
                // Close the socket on cancellation, including during a blocking body read.
                val closeOnCancel = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { body.close() }
                }
                try {
                    val audio = withContext(ioDispatcher) {
                        body.use {
                            if (it.contentType()?.let { type -> "${type.type}/${type.subtype}" } != "audio/wav" ||
                                it.contentLength() > MAX_TTS_AUDIO_BYTES) {
                                throw TtsException(TtsIssue.InvalidAudio)
                            }
                            val output = ByteArrayOutputStream()
                            it.byteStream().use { source ->
                                val buffer = ByteArray(16 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    if (output.size().toLong() + count > MAX_TTS_AUDIO_BYTES) {
                                        throw TtsException(TtsIssue.InvalidAudio)
                                    }
                                    output.write(buffer, 0, count)
                                }
                            }
                            output.toByteArray().also { bytes ->
                                if (bytes.size < 44 || bytes.copyOfRange(0, 4).decodeToString() != "RIFF" ||
                                    bytes.copyOfRange(8, 12).decodeToString() != "WAVE") {
                                    throw TtsException(TtsIssue.InvalidAudio)
                                }
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (!sessions.matches(credentials)) throw TtsException(TtsIssue.SessionExpired)
                    audio
                } finally { closeOnCancel.cancelAndJoin() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SessionChangedException) {
            throw TtsException(TtsIssue.SessionExpired)
        } catch (error: ApiException) {
            throw TtsException(when (error.issue) {
                ApiIssue.SessionExpired -> TtsIssue.SessionExpired
                ApiIssue.Network -> TtsIssue.Network
                ApiIssue.Timeout -> TtsIssue.Timeout
                ApiIssue.InvalidInput -> TtsIssue.InvalidText
                ApiIssue.InvalidResponse -> TtsIssue.InvalidAudio
                else -> TtsIssue.Unavailable
            })
        } catch (_: InterruptedIOException) {
            currentCoroutineContext().ensureActive()
            throw TtsException(TtsIssue.Timeout)
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            throw TtsException(TtsIssue.Network)
        }
    }
}
