package com.zhenting.voicebridge.ui.memo

import com.zhenting.voicebridge.data.model.Memo
import com.zhenting.voicebridge.data.remote.ApiIssue
import com.zhenting.voicebridge.data.local.MemoEditDraft

data class MemoEditUiState(
    val draft: MemoEditDraft, val isSaving: Boolean = false, val isReloading: Boolean = false,
    val error: ApiIssue? = null, val localSaveFailed: Boolean = false
) {
    val isBusy: Boolean get() = isSaving || isReloading
    val needsReview: Boolean get() = draft.outcomeUnknown || error == ApiIssue.Conflict || error == ApiIssue.NotFound
}

data class MemoUiState(
    val draft: String = "",
    val confirmation: String? = null,
    val isSaving: Boolean = false,
    val lastSaved: Memo? = null,
    val memos: List<Memo> = emptyList(),
    val selectedMemoIds: Set<String> = emptySet(),
    val isLoadingHistory: Boolean = false,
    val historyError: ApiIssue? = null,
    val error: ApiIssue? = null,
    val outcomeUnknown: Boolean = false,
    val draftTitle: String = "",
    val searchQuery: String = "",
    val editing: MemoEditUiState? = null,
    val lastSavedWasEdited: Boolean = false
) {
    val visibleMemos: List<Memo> get() {
        val query = searchQuery.trim()
        return if (query.isEmpty()) memos else memos.filter {
            it.title.contains(query, ignoreCase = true) || it.prompt.contains(query, ignoreCase = true)
        }
    }
}
