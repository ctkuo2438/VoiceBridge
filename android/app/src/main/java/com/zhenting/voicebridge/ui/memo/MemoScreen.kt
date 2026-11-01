package com.zhenting.voicebridge.ui.memo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhenting.voicebridge.R
import com.zhenting.voicebridge.data.model.Memo
import com.zhenting.voicebridge.data.model.SheetSaveStatus
import com.zhenting.voicebridge.data.model.MAX_CHAT_MEMOS
import com.zhenting.voicebridge.data.model.MAX_MEMO_CONTEXT_CHARACTERS
import com.zhenting.voicebridge.data.model.asContext
import com.zhenting.voicebridge.data.model.chatCharacterCount
import com.zhenting.voicebridge.data.model.memoContextSize
import com.zhenting.voicebridge.data.model.MAX_MEMO_TITLE_CHARACTERS
import com.zhenting.voicebridge.data.model.defaultMemoTitle
import com.zhenting.voicebridge.data.model.MemoAction
import androidx.compose.material3.OutlinedButton
import com.zhenting.voicebridge.ui.components.RequestError
import com.zhenting.voicebridge.ui.components.AppSymbol
import com.zhenting.voicebridge.ui.components.EmptyState
import com.zhenting.voicebridge.ui.components.SymbolIcon
import com.zhenting.voicebridge.ui.components.displayTimestamp
import com.zhenting.voicebridge.ui.tts.ReadAloudControl
import com.zhenting.voicebridge.ui.tts.TtsUiState

@Composable
fun MemoScreen(
    state: MemoUiState,
    onDraftChanged: (String) -> Unit,
    onRequestSave: () -> Unit,
    onConfirmSave: () -> Unit,
    onCancelSave: () -> Unit,
    onRefreshHistory: () -> Unit,
    onToggleMemo: (String) -> Unit,
    onChatWithSelected: () -> Unit,
    canImportToChat: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    ttsState: TtsUiState = TtsUiState(),
    onReadAloud: (String, String) -> Unit = { _, _ -> },
    onStopSpeech: () -> Unit = {},
    onTitleChanged: (String) -> Unit = {},
    onSearchChanged: (String) -> Unit = {},
    onEditMemo: (String) -> Unit = {},
    onEditTitleChanged: (String) -> Unit = {},
    onEditTextChanged: (String) -> Unit = {},
    onSaveEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onReloadEdit: () -> Unit = {},
    onMemoAction: (Memo, MemoAction) -> Unit = { _, _ -> }
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val selectedMemos = state.memos.filter { it.id in state.selectedMemoIds }
    val selectedCharacters = memoContextSize(selectedMemos.map { it.asContext() })
    val visibleMemos = state.visibleMemos
    LaunchedEffect(Unit) { onRefreshHistory() }
    LaunchedEffect(state.draft) {
        if (state.draft.isNotBlank() && listState.firstVisibleItemIndex > 0) listState.scrollToItem(0)
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("memo_list"),
        state = listState,
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "editor") {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SymbolIcon(AppSymbol.Note)
                        Text(stringResource(R.string.memo_title), style = MaterialTheme.typography.titleLarge)
                    }
                    Text(stringResource(R.string.memo_description), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(state.draftTitle, onTitleChanged,
                        modifier = Modifier.fillMaxWidth().testTag("memo_title_input"),
                        enabled = enabled && !state.isSaving, singleLine = true,
                        label = { Text(stringResource(R.string.memo_title_optional)) },
                        isError = chatCharacterCount(state.draftTitle.trim()) > MAX_MEMO_TITLE_CHARACTERS,
                        supportingText = { Text(stringResource(R.string.memo_title_hint, MAX_MEMO_TITLE_CHARACTERS)) })
                    OutlinedTextField(state.draft, onDraftChanged,
                        modifier = Modifier.fillMaxWidth().testTag("memo_input"),
                        shape = RoundedCornerShape(16.dp),
                        enabled = enabled && !state.isSaving, minLines = 3, maxLines = 8,
                        label = { Text(stringResource(R.string.memo_input_label)) })
                    Button(onClick = { keyboard?.hide(); onRequestSave() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save_memo_button"),
                        enabled = enabled && !state.isSaving && state.draft.isNotBlank() &&
                            chatCharacterCount(state.draftTitle.trim()) <= MAX_MEMO_TITLE_CHARACTERS) {
                        Text(stringResource(R.string.memo_save))
                    }
                }
            }
        }
        if (state.isSaving) item(key = "saving") {
            Column {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.memo_saving), modifier = Modifier.testTag("memo_saving"))
            }
        }
        state.error?.let { issue -> item(key = "save_error") {
            Column {
                if (issue == com.zhenting.voicebridge.data.remote.ApiIssue.InvalidInput) {
                    Text(stringResource(R.string.memo_invalid_input), modifier = Modifier.testTag("memo_error"), color = MaterialTheme.colorScheme.error)
                } else RequestError(issue, Modifier.testTag("memo_error"), uncertain = state.outcomeUnknown)
                Text(stringResource(R.string.memo_draft_kept), style = MaterialTheme.typography.bodySmall)
                if (state.outcomeUnknown) Text(stringResource(R.string.memo_save_unconfirmed),
                    style = MaterialTheme.typography.bodySmall)
            }
        } }
        state.lastSaved?.let { memo -> item(key = "save_receipt") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp).testTag("memo_saved"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SymbolIcon(AppSymbol.Check, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(stringResource(if (state.lastSavedWasEdited) R.string.memo_updated else R.string.memo_saved), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(memo.title, style = MaterialTheme.typography.titleSmall)
                    Text(displayTimestamp(memo.updatedAt), style = MaterialTheme.typography.bodySmall)
                    SheetCopyStatus(memo.sheetStatus)
                }
            }
        } }
        item(key = "history_header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.memo_history), style = MaterialTheme.typography.titleLarge)
                TextButton(onRefreshHistory, enabled = enabled && !state.isLoadingHistory && !state.isSaving,
                    modifier = Modifier.testTag("refresh_memos_button")) {
                    Text(stringResource(R.string.memo_refresh))
                }
            }
        }
        if (state.memos.isNotEmpty() || state.searchQuery.isNotEmpty()) item(key = "search") {
            OutlinedTextField(state.searchQuery, onSearchChanged,
                modifier = Modifier.fillMaxWidth().testTag("memo_search_input"), singleLine = true,
                label = { Text(stringResource(R.string.memo_search)) },
                trailingIcon = if (state.searchQuery.isNotEmpty()) {
                    { TextButton(onClick = { onSearchChanged("") }, modifier = Modifier.testTag("memo_clear_search")) {
                        Text(stringResource(R.string.memo_clear_search))
                    } }
                } else null)
        }
        if (state.isLoadingHistory) item(key = "history_loading") {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("memo_history_loading"))
        }
        if (state.memos.isNotEmpty()) item(key = "memo_selection") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.memo_selection_count, selectedMemos.size, MAX_CHAT_MEMOS),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("memo_selection_count"))
                Button(onClick = { keyboard?.hide(); onChatWithSelected() }, modifier = Modifier.fillMaxWidth().testTag("chat_with_memos_button"),
                    enabled = canImportToChat && selectedMemos.isNotEmpty() &&
                        selectedMemos.size <= MAX_CHAT_MEMOS && selectedCharacters <= MAX_MEMO_CONTEXT_CHARACTERS) {
                    Text(stringResource(R.string.memo_chat_selected))
                }
                if (selectedMemos.isNotEmpty()) Text(stringResource(R.string.memo_context_limit, MAX_MEMO_CONTEXT_CHARACTERS),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        state.historyError?.let { issue -> item(key = "history_error") {
            Column(Modifier.testTag("memo_history_error")) {
                Text(stringResource(R.string.memo_history_failed), style = MaterialTheme.typography.bodyMedium)
                RequestError(issue)
            }
        } }
        if (!state.isLoadingHistory && state.historyError == null && state.memos.isEmpty()) {
            item(key = "history_empty") {
                Column(Modifier.testTag("memo_history_empty")) {
                    EmptyState(AppSymbol.Note, stringResource(R.string.memo_empty_title),
                        stringResource(R.string.memo_empty_description))
                }
            }
        }
        if (state.searchQuery.isNotBlank() && visibleMemos.isEmpty() && !state.isLoadingHistory) {
            item(key = "no_matches") {
                Text(stringResource(R.string.memo_search_empty), modifier = Modifier.testTag("memo_search_empty"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(visibleMemos, key = { "memo_" + it.id }) { memo ->
            val selected = memo.id in state.selectedMemoIds
            val canSelect = enabled && !state.isSaving && (selected || (selectedMemos.size < MAX_CHAT_MEMOS &&
                selectedCharacters + chatCharacterCount(memo.prompt) <= MAX_MEMO_CONTEXT_CHARACTERS))
            MemoHistoryCard(memo, selected, canSelect, ttsState, onReadAloud, onStopSpeech, enabled,
                canEdit = enabled && !state.isSaving, onEdit = { keyboard?.hide(); onEditMemo(memo.id) },
                canAct = canImportToChat && chatCharacterCount(memo.prompt) <= MAX_MEMO_CONTEXT_CHARACTERS,
                onAction = { keyboard?.hide(); onMemoAction(memo, it) },
                onToggle = { onToggleMemo(memo.id) })
        }
    }
    state.confirmation?.let { prompt ->
        AlertDialog(onDismissRequest = onCancelSave,
            title = { Text(stringResource(R.string.memo_confirm_title)) },
            text = { SelectionContainer { Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.draftTitle.trim().ifBlank { defaultMemoTitle(prompt) }, style = MaterialTheme.typography.titleMedium)
                Text(prompt)
            } } },
            confirmButton = { TextButton(onConfirmSave, modifier = Modifier.testTag("confirm_save_memo_button"),
                enabled = enabled && !state.isSaving) { Text(stringResource(R.string.memo_confirm)) } },
            dismissButton = { TextButton(onCancelSave) { Text(stringResource(R.string.memo_keep_editing)) } })
    }
    state.editing?.let { editing ->
        MemoEditDialog(editing, enabled, onEditTitleChanged, onEditTextChanged, onSaveEdit, onCancelEdit, onReloadEdit)
    }
}

@Composable
private fun MemoHistoryCard(
    memo: Memo, selected: Boolean, canSelect: Boolean,
    ttsState: TtsUiState, onReadAloud: (String, String) -> Unit,
    onStopSpeech: () -> Unit, ttsEnabled: Boolean, canEdit: Boolean, onEdit: () -> Unit,
    canAct: Boolean, onAction: (MemoAction) -> Unit, onToggle: () -> Unit
) {
    var expanded by rememberSaveable(memo.id) { mutableStateOf(false) }
    var canExpand by rememberSaveable(memo.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("memo_${memo.id}"),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (memo.version > 0) stringResource(R.string.memo_edited_at, displayTimestamp(memo.updatedAt))
                    else displayTimestamp(memo.createdAt), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Checkbox(selected, onCheckedChange = { onToggle() }, enabled = canSelect,
                    modifier = Modifier.testTag("select_memo_${memo.id}"))
            }
            Text(memo.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("memo_title_${memo.id}"))
            SelectionContainer {
                Text(memo.prompt, maxLines = if (expanded) Int.MAX_VALUE else 6,
                    style = MaterialTheme.typography.bodyLarge,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { layout -> if (!expanded) canExpand = layout.hasVisualOverflow })
            }
            if (canExpand || expanded) TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.memo_show_less else R.string.memo_show_more))
            }
            for (actions in MemoAction.entries.chunked(2)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (action in actions) OutlinedButton(onClick = { onAction(action) }, enabled = canAct,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("memo_action_${action.apiValue}_${memo.id}")) {
                        Text(stringResource(when (action) {
                            MemoAction.Summary -> R.string.memo_action_summary
                            MemoAction.Todos -> R.string.memo_action_todos
                            MemoAction.Bilingual -> R.string.memo_action_bilingual
                            MemoAction.Ask -> R.string.memo_action_ask
                        }))
                    }
                }
            }
            SheetCopyStatus(memo.sheetStatus)
            TextButton(onEdit, enabled = canEdit, modifier = Modifier.testTag("edit_memo_${memo.id}")) {
                Text(stringResource(R.string.memo_edit))
            }
            ReadAloudControl("memo:${memo.id}", memo.prompt, ttsState, onReadAloud, onStopSpeech, ttsEnabled)
            if (chatCharacterCount(memo.prompt) > MAX_MEMO_CONTEXT_CHARACTERS) {
                Text(stringResource(R.string.memo_too_long_for_chat), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SheetCopyStatus(status: SheetSaveStatus) {
    Text(stringResource(when (status) {
        SheetSaveStatus.Saved -> R.string.memo_sheet_saved
        SheetSaveStatus.Unconfirmed -> R.string.memo_sheet_unconfirmed
        SheetSaveStatus.Outdated -> R.string.memo_sheet_outdated
    }),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MemoEditDialog(
    state: MemoEditUiState, enabled: Boolean, onTitle: (String) -> Unit, onText: (String) -> Unit,
    onSave: () -> Unit, onCancel: () -> Unit, onReload: () -> Unit
) {
    val draft = state.draft
    AlertDialog(onDismissRequest = { if (!state.isBusy) onCancel() },
        title = { Text(stringResource(R.string.memo_edit)) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(draft.title, onTitle, modifier = Modifier.fillMaxWidth().testTag("memo_edit_title"),
                    enabled = enabled && !state.isBusy, readOnly = state.needsReview, singleLine = true,
                    label = { Text(stringResource(R.string.memo_title_label)) },
                    isError = draft.title.isBlank() || chatCharacterCount(draft.title.trim()) > MAX_MEMO_TITLE_CHARACTERS,
                    supportingText = { Text(stringResource(R.string.memo_title_limit, MAX_MEMO_TITLE_CHARACTERS)) })
                OutlinedTextField(draft.text, onText, modifier = Modifier.fillMaxWidth().testTag("memo_edit_text"),
                    enabled = enabled && !state.isBusy, readOnly = state.needsReview, minLines = 3, maxLines = 8,
                    label = { Text(stringResource(R.string.memo_input_label)) })
                if (state.isBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.localSaveFailed) Text(stringResource(R.string.draft_storage_failed), color = MaterialTheme.colorScheme.error)
                state.error?.let { issue ->
                    Text(stringResource(when (issue) {
                        com.zhenting.voicebridge.data.remote.ApiIssue.Conflict -> R.string.memo_edit_conflict
                        com.zhenting.voicebridge.data.remote.ApiIssue.NotFound -> R.string.memo_edit_missing
                        com.zhenting.voicebridge.data.remote.ApiIssue.InvalidInput -> R.string.memo_invalid_input
                        else -> if (draft.outcomeUnknown) R.string.memo_edit_uncertain else R.string.memo_edit_failed
                    }), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("memo_edit_error"))
                }
                if (draft.outcomeUnknown && state.error == null) Text(stringResource(R.string.memo_edit_uncertain))
                if (state.needsReview || state.error == com.zhenting.voicebridge.data.remote.ApiIssue.NotFound) {
                    TextButton(onReload, enabled = enabled && !state.isBusy, modifier = Modifier.testTag("memo_edit_reload")) {
                        Text(stringResource(R.string.memo_edit_reload))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onSave, enabled = enabled && !state.isBusy && state.error != com.zhenting.voicebridge.data.remote.ApiIssue.Conflict &&
                state.error != com.zhenting.voicebridge.data.remote.ApiIssue.NotFound && draft.title.isNotBlank() && draft.text.isNotBlank() &&
                chatCharacterCount(draft.title.trim()) <= MAX_MEMO_TITLE_CHARACTERS,
                modifier = Modifier.testTag("memo_edit_save")) { Text(stringResource(R.string.memo_save_changes)) }
        },
        dismissButton = { TextButton(onCancel, enabled = !state.isBusy,
            modifier = Modifier.testTag("memo_edit_cancel")) { Text(stringResource(R.string.action_cancel)) } })
}
