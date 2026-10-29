package com.zhenting.voicebridge.ui.memo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhenting.voicebridge.data.remote.ApiException
import com.zhenting.voicebridge.data.remote.ApiIssue
import com.zhenting.voicebridge.data.repository.MemoRepository
import com.zhenting.voicebridge.data.model.MAX_CHAT_MEMOS
import com.zhenting.voicebridge.data.model.MAX_MEMO_CONTEXT_CHARACTERS
import com.zhenting.voicebridge.data.model.asContext
import com.zhenting.voicebridge.data.model.memoContextSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class MemoViewModel(private val repository: MemoRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(MemoUiState())
    val uiState = _uiState.asStateFlow()
    private data class PendingSave(val prompt: String, val requestId: String)
    private var pendingSave: PendingSave? = null
    private var historyGeneration = 0
    private var historyJob: Job? = null

    fun refreshHistory() {
        val version = ++historyGeneration
        historyJob?.cancel()
        _uiState.update { it.copy(isLoadingHistory = true, historyError = null) }
        historyJob = viewModelScope.launch {
            try {
                val memos = repository.memos()
                if (version == historyGeneration) _uiState.update { state ->
                    state.copy(memos = memos,
                        selectedMemoIds = state.selectedMemoIds.intersect(memos.map { it.id }.toSet()),
                        lastSaved = state.lastSaved?.let { saved -> memos.find { it.id == saved.id } ?: saved })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ApiException) {
                if (version == historyGeneration) _uiState.update { it.copy(historyError = error.issue) }
            } finally {
                if (version == historyGeneration) _uiState.update { it.copy(isLoadingHistory = false) }
            }
        }
    }

    fun toggleMemo(id: String) {
        val state = _uiState.value
        if (state.isSaving || state.memos.none { it.id == id }) return
        val ids = if (id in state.selectedMemoIds) state.selectedMemoIds - id else state.selectedMemoIds + id
        val selected = state.memos.filter { it.id in ids }.map { it.asContext() }
        if (selected.size <= MAX_CHAT_MEMOS && memoContextSize(selected) <= MAX_MEMO_CONTEXT_CHARACTERS) {
            _uiState.update { it.copy(selectedMemoIds = ids) }
        }
    }

    fun clearSelection() { _uiState.update { it.copy(selectedMemoIds = emptySet()) } }

    fun editDraft(text: String) {
        if (!_uiState.value.isSaving) _uiState.update {
            it.copy(draft = text, confirmation = null, error = null, outcomeUnknown = false)
        }
    }

    fun requestSave() {
        val state = _uiState.value
        if (state.isSaving) return
        if (state.draft.isBlank()) {
            _uiState.update { it.copy(error = ApiIssue.InvalidInput) }
        } else {
            _uiState.update { it.copy(confirmation = it.draft.trim()) }
        }
    }

    fun cancelConfirmation() { _uiState.update { it.copy(confirmation = null) } }

    fun saveConfirmed() {
        val state = _uiState.value
        val prompt = state.confirmation ?: return
        if (state.isSaving) return
        // Keep the same request ID if the response is lost and the user confirms the same note again.
        val pending = pendingSave?.takeIf { it.prompt == prompt }
            ?: PendingSave(prompt, UUID.randomUUID().toString()).also { pendingSave = it }
        _uiState.update {
            it.copy(isSaving = true, confirmation = null, error = null, outcomeUnknown = false)
        }
        viewModelScope.launch {
            try {
                val memo = repository.save(prompt, pending.requestId)
                pendingSave = null
                // A history request started before this save must not replace the newly saved note.
                historyGeneration++
                historyJob?.cancel()
                _uiState.update {
                    it.copy(draft = "", lastSaved = memo,
                        memos = listOf(memo) + it.memos.filterNot { item -> item.id == memo.id })
                }
                refreshHistory()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ApiException) {
                _uiState.update { it.copy(error = error.issue, outcomeUnknown = error.outcomeUnknown) }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    companion object {
        fun factory(repository: MemoRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { MemoViewModel(repository) }
        }
    }
}
