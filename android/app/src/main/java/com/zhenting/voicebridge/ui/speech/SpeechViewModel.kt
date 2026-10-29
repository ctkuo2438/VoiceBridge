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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class SpeechViewModel(
    private val recorder: AudioRecorder,
    private val repository: SpeechRepository,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) : ViewModel() {
    private val _uiState = MutableStateFlow(SpeechUiState())
    val uiState = _uiState.asStateFlow()
    private var timer: Job? = null
    private var transcription: Job? = null
    private var upload: File? = null
    private var generation = 0

    fun start(target: SpeechTarget) {
        if (_uiState.value.isBusy) return
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
        val version = generation
        val file = try {
            recorder.stop()
        } catch (error: SpeechException) {
            recorder.cancel()
            _uiState.update { it.copy(phase = SpeechPhase.Idle, error = error.issue) }
            return
        }
        upload = file
        _uiState.update { it.copy(phase = SpeechPhase.Transcribing) }
        transcription = viewModelScope.launch {
            try {
                val text = repository.transcribe(file)
                if (version == generation) _uiState.update {
                    it.copy(phase = SpeechPhase.Review, text = text, error = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SpeechException) {
                if (version == generation) _uiState.update {
                    it.copy(phase = SpeechPhase.Idle, error = error.issue)
                }
            } finally {
                file.delete()
                if (upload === file) upload = null
            }
        }
    }

    fun editTranscript(text: String) {
        if (_uiState.value.phase == SpeechPhase.Review) _uiState.update { it.copy(text = text) }
    }

    fun acceptTranscript(onAccept: (SpeechTarget, String) -> Unit) {
        val state = _uiState.value
        val target = state.target ?: return
        if (state.phase != SpeechPhase.Review || state.text.isBlank()) return
        onAccept(target, state.text.trim())
        _uiState.value = SpeechUiState()
    }

    fun permissionDenied() {
        if (!_uiState.value.isBusy) _uiState.value = SpeechUiState(error = SpeechIssue.PermissionDenied)
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
        upload?.delete()
        upload = null
        _uiState.value = SpeechUiState()
    }

    override fun onCleared() { cancel() }

    companion object {
        fun factory(createRecorder: () -> AudioRecorder, repository: SpeechRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { SpeechViewModel(createRecorder(), repository) }
        }
    }
}
