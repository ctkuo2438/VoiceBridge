package com.zhenting.voicebridge.data.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.zhenting.voicebridge.data.model.TtsException
import com.zhenting.voicebridge.data.model.TtsIssue
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface AudioPlayer {
    suspend fun play(audio: ByteArray, onStarted: () -> Unit)
    fun stop()
}

/** Foreground spoken audio using the device's media volume and current audio route. */
class AndroidAudioPlayer(context: Context) : AudioPlayer {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var active: Playback? = null

    private class Playback(
        val media: MediaPlayer,
        val data: WavDataSource,
        val continuation: CancellableContinuation<Unit>,
        var focus: AudioFocusRequest? = null,
        var receiver: BroadcastReceiver? = null
    )

    override suspend fun play(audio: ByteArray, onStarted: () -> Unit) = withContext(Dispatchers.Main.immediate) {
        stop()
        suspendCancellableCoroutine<Unit> { continuation ->
            val playback = Playback(MediaPlayer(), WavDataSource(audio), continuation)
            active = playback
            continuation.invokeOnCancellation {
                if (Looper.myLooper() == Looper.getMainLooper()) finish(playback)
                else handler.post { finish(playback) }
            }
            try {
                playback.media.setAudioAttributes(attributes)
                playback.media.setDataSource(playback.data)
                playback.media.setOnPreparedListener {
                    if (active !== playback || !continuation.isActive) return@setOnPreparedListener
                    try {
                        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(attributes)
                            .setWillPauseWhenDucked(true)
                            .setOnAudioFocusChangeListener({ change ->
                                if (change < 0) finish(playback, TtsIssue.Interrupted)
                            }, handler).build()
                        playback.focus = focus
                        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                            finish(playback, TtsIssue.PlaybackUnavailable)
                            return@setOnPreparedListener
                        }
                        val receiver = object : BroadcastReceiver() {
                            override fun onReceive(context: Context?, intent: Intent?) {
                                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                                    finish(playback, TtsIssue.Interrupted)
                                }
                            }
                        }
                        ContextCompat.registerReceiver(appContext, receiver,
                            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
                        playback.receiver = receiver
                        playback.media.start()
                        onStarted()
                    } catch (_: RuntimeException) { finish(playback, TtsIssue.PlaybackUnavailable) }
                }
                playback.media.setOnCompletionListener { finish(playback, completed = true) }
                playback.media.setOnErrorListener { _, _, _ ->
                    finish(playback, TtsIssue.PlaybackUnavailable)
                    true
                }
                playback.media.prepareAsync()
            } catch (_: Exception) { finish(playback, TtsIssue.PlaybackUnavailable) }
        }
    }

    override fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { stop() }
            return
        }
        active?.let { finish(it, TtsIssue.Interrupted) }
    }

    private fun finish(playback: Playback, issue: TtsIssue? = null, completed: Boolean = false) {
        if (active !== playback) return
        active = null
        playback.receiver?.let { receiver -> runCatching { appContext.unregisterReceiver(receiver) } }
        runCatching { playback.media.release() }
        playback.data.close()
        playback.focus?.let { focus -> runCatching { manager.abandonAudioFocusRequest(focus) } }
        if (playback.continuation.isActive) {
            if (completed) playback.continuation.resume(Unit)
            else playback.continuation.resumeWithException(TtsException(issue ?: TtsIssue.Interrupted))
        }
    }

    private class WavDataSource(audio: ByteArray) : MediaDataSource() {
        @Volatile private var data: ByteArray? = audio
        override fun getSize(): Long = data?.size?.toLong() ?: 0L
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            val bytes = data ?: return -1
            if (size == 0) return 0
            if (position < 0 || position >= bytes.size) return -1
            val count = minOf(size, bytes.size - position.toInt())
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }
        override fun close() { data = null }
    }
}
