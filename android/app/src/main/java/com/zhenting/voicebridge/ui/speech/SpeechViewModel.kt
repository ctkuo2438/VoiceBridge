package com.zhenting.voicebridge.ui.speech

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhenting.voicebridge.data.audio.AudioRecorder
import com.zhenting.voicebridge.data.model.MAX_RECORDING_SECONDS
import com.zhenting.voicebridge.data.model.SpeechException
import com.zhenting.voicebridge.data.model.SpeechIssue
import com.zhenting.voicebridge.data.repository.SpeechRepository
import com.zhenting.voicebridge.data.local.RecoveryStore
import com.zhenting.voicebridge.data.local.PendingSpeech
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class SpeechViewModel(
    private val recorder: AudioRecorder,
    private val repository: SpeechRepository,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val recovery: RecoveryStore = RecoveryStore()
) : ViewModel() {
    private val _uiState = MutableStateFlow(recovery.snapshot.speech?.let {
        SpeechUiState(if (it.text != null) SpeechPhase.Review else SpeechPhase.Retry,
            it.target, it.elapsedSeconds, it.text.orEmpty())
    } ?: SpeechUiState())
    val uiState = _uiState.asStateFlow()
    private var timer: Job? = null
    private var transcription: Job? = null
    private var upload: File? = null
    private var generation = 0

    init {
        recovery.snapshot.speech?.let {
            upload = File(it.audioPath)
            if (it.text != null) {
                // The same account can return while an earlier disk write is still pending.
                val file = upload
                viewModelScope.launch { if (recovery.flush()) file?.delete() }
            } else if (upload?.isFile != true) {
                recovery.update { saved -> saved.copy(speech = null) }
                upload = null
                _uiState.value = SpeechUiState(error = SpeechIssue.NoAudio)
            }
        }
    }

    fun start(target: SpeechTarget) {
        if (_uiState.value.phase != SpeechPhase.Idle) return
        val version = ++generation
        try {
            recorder.start {
                if (version == generation) {
                    cancel()
                    _uiState.value = SpeechUiState(target = target, error = SpeechIssue.RecordingUnavailable)
                }
            }
            val started = nowMillis()
            _uiState.value = SpeechUiState(phase = SpeechPhase.Recording, target = target)
            timer = viewModelScope.launch {
                while (version == generation && _uiState.value.phase == SpeechPhase.Recording) {
                    delay(1000)
                    val elapsed = ((nowMillis() - started) / 1000).toInt()
                    _uiState.update { it.copy(elapsedSeconds = elapsed) }
                    if (elapsed >= MAX_RECORDING_SECONDS) { stopAndTranscribe(); break }
                }
            }
        } catch (error: SpeechException) {
            recorder.cancel()
            _uiState.value = SpeechUiState(target = target, error = error.issue)
        }
    }

    fun stopAndTranscribe() {
        if (_uiState.value.phase != SpeechPhase.Recording) return
        timer?.cancel()
        val file = try {
            recorder.stop()
        } catch (error: SpeechException) {
            recorder.cancel()
            _uiState.update { it.copy(phase = SpeechPhase.Idle, error = error.issue) }
            return
        }
        upload = file
        val state = _uiState.value
        val pending = PendingSpeech(UUID.randomUUID().toString(), checkNotNull(state.target),
            file.absolutePath, elapsedSeconds = state.elapsedSeconds)
        recovery.update { it.copy(speech = pending, destination = pending.target) }
        transcribe(pending)
    }

    fun retry() {
        if (_uiState.value.phase != SpeechPhase.Retry) return
        recovery.snapshot.speech?.let(::transcribe)
    }

    private fun transcribe(pending: PendingSpeech) {
        val version = ++generation
        val file = File(pending.audioPath)
        upload = file
        _uiState.value = SpeechUiState(SpeechPhase.Transcribing, pending.target, pending.elapsedSeconds)
        transcription = viewModelScope.launch {
            try {
                // Register the completed clip before a network request or deleting any source audio.
                if (!recovery.flush()) throw SpeechException(SpeechIssue.LocalStorage)
                val text = pending.text ?: repository.transcribe(file)
                if (version != generation) return@launch
                recovery.update { it.copy(speech = pending.copy(text = text)) }
                if (!recovery.flush()) throw SpeechException(SpeechIssue.LocalStorage)
                if (version != generation) return@launch
                file.delete()
                upload = null
                _uiState.value = SpeechUiState(SpeechPhase.Review, pending.target, pending.elapsedSeconds, text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SpeechException) {
                if (version == generation) _uiState.update {
                    it.copy(phase = SpeechPhase.Retry, error = error.issue)
                }
            }
        }
    }

    fun editTranscript(text: String) {
        if (_uiState.value.phase == SpeechPhase.Review) {
            recovery.update { it.copy(speech = it.speech?.copy(text = text)) }
            _uiState.update { it.copy(text = text) }
        }
    }

    fun acceptTranscript(onAccept: (SpeechTarget, String) -> Unit) {
        val state = _uiState.value
        val target = state.target ?: return
        if (state.phase != SpeechPhase.Review || state.text.isBlank()) return
        val pending = recovery.snapshot.speech ?: return
        val accepted = recovery.consumeSpeech(pending.id) ?: return
        onAccept(target, accepted.second)
        viewModelScope.launch { if (recovery.flush()) File(pending.audioPath).delete() }
        upload = null
        _uiState.value = SpeechUiState()
    }

    fun permissionDenied() {
        if (_uiState.value.phase == SpeechPhase.Idle) _uiState.value = SpeechUiState(error = SpeechIssue.PermissionDenied)
    }

    fun stopForBackground() {
        if (_uiState.value.phase == SpeechPhase.Recording) {
            cancel()
            _uiState.value = SpeechUiState(error = SpeechIssue.Interrupted)
        }
    }

    fun cancel() {
        generation++
        timer?.cancel()
        transcription?.cancel()
        recorder.cancel()
        recovery.snapshot.speech?.let { File(it.audioPath).delete() }
        recovery.update { it.copy(speech = null) }
        upload?.delete()
        upload = null
        _uiState.value = SpeechUiState()
    }

    override fun onCleared() {
        generation++
        timer?.cancel()
        transcription?.cancel()
        recorder.cancel()
        // Completed clips and review text belong to the account and survive workspace destruction.
    }

    companion object {
        fun factory(createRecorder: () -> AudioRecorder, repository: SpeechRepository,
            recovery: RecoveryStore = RecoveryStore()): ViewModelProvider.Factory = viewModelFactory {
            initializer { SpeechViewModel(createRecorder(), repository, recovery = recovery) }
        }
    }
}
