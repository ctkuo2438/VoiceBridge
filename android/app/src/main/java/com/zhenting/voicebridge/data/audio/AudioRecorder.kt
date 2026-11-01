package com.zhenting.voicebridge.data.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import com.zhenting.voicebridge.data.model.SpeechException
import com.zhenting.voicebridge.data.model.SpeechIssue
import java.io.File
import java.io.IOException

interface AudioRecorder {
    fun start(onError: () -> Unit)
    fun stop(): File
    fun cancel()
}

/** Records mono AAC in an M4A container in an app-private directory. */
class AndroidAudioRecorder(context: Context, private val directory: File = File(context.cacheDir, "voice-recordings")) : AudioRecorder {
    private val appContext = context.applicationContext
    private var recorder: MediaRecorder? = null
    private var output: File? = null

    init {
        directory.mkdirs()
        // RecoveryStore removes unregistered scratch files after loading the account's pending clip.
    }

    @Suppress("DEPRECATION")
    @Synchronized
    override fun start(onError: () -> Unit) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) throw SpeechException(SpeechIssue.PermissionDenied)
        if (recorder != null) throw SpeechException(SpeechIssue.RecordingUnavailable)
        try {
            val file = File.createTempFile("voice-", ".m4a", directory)
            output = file
            val media = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(appContext)
                else MediaRecorder()
            recorder = media
            media.setAudioSource(MediaRecorder.AudioSource.MIC)
            media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            media.setAudioChannels(1)
            media.setAudioSamplingRate(44_100)
            media.setAudioEncodingBitRate(64_000)
            media.setOutputFile(file.absolutePath)
            media.setOnErrorListener { _, _, _ -> if (recorder === media) onError() }
            media.prepare()
            media.start()
        } catch (_: SecurityException) {
            cancel()
            throw SpeechException(SpeechIssue.PermissionDenied)
        } catch (_: IOException) {
            cancel()
            throw SpeechException(SpeechIssue.RecordingUnavailable)
        } catch (_: RuntimeException) {
            cancel()
            throw SpeechException(SpeechIssue.RecordingUnavailable)
        }
    }

    @Synchronized
    override fun stop(): File {
        val media = recorder ?: throw SpeechException(SpeechIssue.NoAudio)
        val file = output ?: throw SpeechException(SpeechIssue.NoAudio)
        recorder = null
        output = null
        try {
            media.stop()
        } catch (_: RuntimeException) {
            file.delete()
            throw SpeechException(SpeechIssue.NoAudio)
        } finally {
            try { media.release() } catch (_: RuntimeException) { /* Already released. */ }
        }
        if (file.length() == 0L) {
            file.delete()
            throw SpeechException(SpeechIssue.NoAudio)
        }
        return file
    }

    @Synchronized
    override fun cancel() {
        val media = recorder
        recorder = null
        try { media?.release() } catch (_: RuntimeException) { /* Already released. */ } finally {
            output?.delete()
            output = null
        }
    }
}
