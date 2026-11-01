package com.zhenting.voicebridge.ui.memo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhenting.voicebridge.data.remote.ApiException
import com.zhenting.voicebridge.data.remote.ApiIssue
import com.zhenting.voicebridge.data.repository.MemoRepository
import com.zhenting.voicebridge.data.local.RecoveryStore
import com.zhenting.voicebridge.data.local.MemoDraft
import com.zhenting.voicebridge.data.local.MemoEditDraft
import com.zhenting.voicebridge.data.local.DraftDestination
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.zhenting.voicebridge.data.model.MAX_CHAT_MEMOS
import com.zhenting.voicebridge.data.model.Memo
import com.zhenting.voicebridge.data.model.MAX_MEMO_TITLE_CHARACTERS
import com.zhenting.voicebridge.data.model.chatCharacterCount
import com.zhenting.voicebridge.data.model.defaultMemoTitle
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

class MemoViewModel(private val repository: MemoRepository, private val recovery: RecoveryStore = RecoveryStore()) : ViewModel() {
    private val _uiState = MutableStateFlow(MemoUiState(draft = recovery.snapshot.memo.text,
        outcomeUnknown = recovery.snapshot.memo.requestId != null, draftTitle = recovery.snapshot.memo.title,
        editing = recovery.snapshot.memoEdit?.let { MemoEditUiState(it) }))
    val uiState = _uiState.asStateFlow()
    private data class PendingSave(val prompt: String, val title: String, val requestId: String)
    private var pendingSave: PendingSave? = recovery.snapshot.memo.let {
        if (it.pendingPrompt != null && it.requestId != null)
            PendingSave(it.pendingPrompt, it.pendingTitle ?: defaultMemoTitle(it.pendingPrompt), it.requestId) else null
    }
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

    fun search(query: String) { _uiState.update { it.copy(searchQuery = query) } }

    fun editDraftTitle(title: String) {
        if (_uiState.value.isSaving) return
        recovery.update { it.copy(memo = it.memo.copy(title = title), destination = DraftDestination.Memo) }
        _uiState.update { it.copy(draftTitle = title, confirmation = null, error = null, outcomeUnknown = false) }
    }

    fun editDraft(text: String) {
        if (!_uiState.value.isSaving) {
            recovery.update { it.copy(memo = it.memo.copy(text = text), destination = DraftDestination.Memo) }
            _uiState.update { it.copy(draft = text, confirmation = null, error = null, outcomeUnknown = false) }
        }
    }

    fun requestSave() {
        val state = _uiState.value
        if (state.isSaving) return
        if (state.draft.isBlank() || chatCharacterCount(state.draftTitle.trim()) > MAX_MEMO_TITLE_CHARACTERS) {
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
        val title = state.draftTitle.trim().ifBlank { defaultMemoTitle(prompt) }
        // Keep the same request ID if the response is lost and the user confirms the same note again.
        val pending = pendingSave?.takeIf { it.prompt == prompt && it.title == title }
            ?: PendingSave(prompt, title, UUID.randomUUID().toString()).also { pendingSave = it }
        recovery.update { it.copy(memo = MemoDraft(state.draft, pending.prompt, pending.requestId, state.draftTitle, pending.title)) }
        _uiState.update {
            it.copy(isSaving = true, confirmation = null, error = null, outcomeUnknown = false)
        }
        viewModelScope.launch {
            try {
                // Persist the idempotency key first, so restarting after a lost response can retry safely.
                if (!recovery.flush()) return@launch
                val memo = repository.save(prompt, pending.requestId, pending.title)
                currentCoroutineContext().ensureActive()
                pendingSave = null
                recovery.update { it.copy(memo = MemoDraft()) }
                // A history request started before this save must not replace the newly saved note.
                historyGeneration++
                historyJob?.cancel()
                _uiState.update {
                    it.copy(draft = "", draftTitle = "", lastSaved = memo, lastSavedWasEdited = false,
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

    fun startEditing(memoId: String) {
        if (_uiState.value.isSaving || _uiState.value.editing != null) return
        val memo = _uiState.value.memos.find { it.id == memoId } ?: return
        val draft = MemoEditDraft(memo)
        recovery.update { it.copy(memoEdit = draft, destination = DraftDestination.Memo) }
        _uiState.update { it.copy(editing = MemoEditUiState(draft)) }
    }

    private fun changeEdit(transform: (MemoEditDraft) -> MemoEditDraft) {
        val editing = _uiState.value.editing ?: return
        if (editing.isBusy || editing.needsReview) return
        val draft = transform(editing.draft)
        recovery.update { it.copy(memoEdit = draft) }
        _uiState.update { it.copy(editing = editing.copy(draft = draft, error = null, localSaveFailed = false)) }
    }

    fun editSavedTitle(title: String) = changeEdit { it.copy(title = title) }
    fun editSavedText(text: String) = changeEdit { it.copy(text = text) }

    fun cancelEditing() {
        val editing = _uiState.value.editing ?: return
        if (editing.isBusy) return
        recovery.update { it.copy(memoEdit = null) }
        _uiState.update { it.copy(editing = null) }
        if (editing.needsReview) refreshHistory()
    }

    fun saveEditedMemo() {
        val editing = _uiState.value.editing ?: return
        val draft = editing.draft
        if (editing.isBusy || editing.error == ApiIssue.Conflict) return
        if (draft.title.isBlank() || chatCharacterCount(draft.title.trim()) > MAX_MEMO_TITLE_CHARACTERS || draft.text.isBlank()) {
            _uiState.update { it.copy(editing = editing.copy(error = ApiIssue.InvalidInput)) }
            return
        }
        recovery.update { it.copy(memoEdit = draft.copy(outcomeUnknown = true)) }
        _uiState.update { it.copy(editing = editing.copy(isSaving = true, error = null, localSaveFailed = false)) }
        viewModelScope.launch {
            try {
                if (!recovery.flush()) {
                    recovery.update { it.copy(memoEdit = draft) }
                    _uiState.update { it.copy(editing = editing.copy(localSaveFailed = true)) }
                    return@launch
                }
                val memo = repository.update(draft.memo.id, draft.title.trim(), draft.text.trim(), draft.memo.version)
                currentCoroutineContext().ensureActive()
                historyGeneration++
                historyJob?.cancel()
                recovery.update { it.copy(memoEdit = null) }
                _uiState.update {
                    it.copy(editing = null, lastSaved = memo, lastSavedWasEdited = true, isLoadingHistory = false,
                        memos = (listOf(memo) + it.memos.filterNot { old -> old.id == memo.id }).sortedByDescending { note -> note.updatedAt })
                }
                refreshHistory()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ApiException) {
                val failedDraft = draft.copy(outcomeUnknown = error.outcomeUnknown)
                recovery.update { it.copy(memoEdit = failedDraft) }
                _uiState.update { it.copy(editing = editing.copy(draft = failedDraft, error = error.issue)) }
            } finally {
                _uiState.update { it.copy(editing = it.editing?.copy(isSaving = false)) }
            }
        }
    }

    fun reloadEditedMemo() {
        val editing = _uiState.value.editing ?: return
        if (editing.isBusy) return
        _uiState.update { it.copy(editing = editing.copy(isReloading = true, error = null)) }
        viewModelScope.launch {
            try {
                val memos = repository.memos()
                currentCoroutineContext().ensureActive()
                val memo = memos.find { it.id == editing.draft.memo.id } ?: throw ApiException(ApiIssue.NotFound)
                val draft = MemoEditDraft(memo)
                recovery.update { it.copy(memoEdit = draft) }
                historyGeneration++
                historyJob?.cancel()
                _uiState.update {
                    it.copy(memos = memos, editing = MemoEditUiState(draft), isLoadingHistory = false,
                        selectedMemoIds = it.selectedMemoIds.intersect(memos.map { note -> note.id }.toSet()))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ApiException) {
                _uiState.update { it.copy(editing = editing.copy(error = error.issue)) }
            } finally {
                _uiState.update { it.copy(editing = it.editing?.copy(isReloading = false)) }
            }
        }
    }

    companion object {
        fun factory(repository: MemoRepository, recovery: RecoveryStore = RecoveryStore()): ViewModelProvider.Factory = viewModelFactory {
            initializer { MemoViewModel(repository, recovery) }
        }
    }
}
