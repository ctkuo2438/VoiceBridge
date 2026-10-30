package com.zhenting.voicebridge.ui.tts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhenting.voicebridge.data.audio.AudioPlayer
import com.zhenting.voicebridge.data.model.TtsException
import com.zhenting.voicebridge.data.model.TtsIssue
import com.zhenting.voicebridge.data.model.speechChunks
import com.zhenting.voicebridge.data.repository.TtsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TtsViewModel(private val repository: TtsRepository, private val player: AudioPlayer) : ViewModel() {
    private val _uiState = MutableStateFlow(TtsUiState())
    val uiState = _uiState.asStateFlow()
    private var request: Job? = null
    private var generation = 0

    fun speak(itemId: String, text: String) {
        stop()
        val parts = speechChunks(text)
        if (parts.isEmpty()) {
            _uiState.value = TtsUiState(itemId = itemId, error = TtsIssue.InvalidText)
            return
        }
        val version = generation
        _uiState.value = TtsUiState(itemId, TtsPhase.Generating, 1, parts.size)
        request = viewModelScope.launch {
            try {
                for ((index, part) in parts.withIndex()) {
                    if (version != generation) return@launch
                    _uiState.value = TtsUiState(itemId, TtsPhase.Generating, index + 1, parts.size)
                    val audio = repository.synthesize(part)
                    currentCoroutineContext().ensureActive()
                    if (version != generation) return@launch
                    _uiState.value = TtsUiState(itemId, TtsPhase.Preparing, index + 1, parts.size)
                    player.play(audio) {
                        if (version == generation) _uiState.value =
                            TtsUiState(itemId, TtsPhase.Playing, index + 1, parts.size)
                    }
                }
                if (version == generation) _uiState.value = TtsUiState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: TtsException) {
                if (version == generation) _uiState.value = TtsUiState(itemId = itemId, error = error.issue)
            } finally {
                if (version == generation) player.stop()
            }
        }
    }

    fun stop() {
        generation++
        request?.cancel()
        request = null
        player.stop()
        _uiState.value = TtsUiState()
    }

    override fun onCleared() { stop() }

    companion object {
        fun factory(repository: TtsRepository, createPlayer: () -> AudioPlayer): ViewModelProvider.Factory = viewModelFactory {
            initializer { TtsViewModel(repository, createPlayer()) }
        }
    }
}
