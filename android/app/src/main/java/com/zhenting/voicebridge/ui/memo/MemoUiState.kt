package com.zhenting.voicebridge.ui.memo

import com.zhenting.voicebridge.data.model.Memo
import com.zhenting.voicebridge.data.remote.ApiIssue

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
    val outcomeUnknown: Boolean = false
)
