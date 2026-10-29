package com.zhenting.voicebridge.ui.memo

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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.zhenting.voicebridge.ui.components.RequestError
import com.zhenting.voicebridge.ui.components.displayTimestamp

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
    enabled: Boolean = true
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val selectedMemos = state.memos.filter { it.id in state.selectedMemoIds }
    val selectedCharacters = memoContextSize(selectedMemos.map { it.asContext() })
    LaunchedEffect(Unit) { onRefreshHistory() }
    LaunchedEffect(state.draft) {
        if (state.draft.isNotBlank() && listState.firstVisibleItemIndex > 0) listState.scrollToItem(0)
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("memo_list"),
        state = listState,
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "editor") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.memo_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.memo_description), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(state.draft, onDraftChanged,
                    modifier = Modifier.fillMaxWidth().testTag("memo_input"),
                    enabled = enabled && !state.isSaving, minLines = 4, maxLines = 12,
                    label = { Text(stringResource(R.string.memo_input_label)) })
                Button(onClick = { keyboard?.hide(); onRequestSave() },
                    modifier = Modifier.fillMaxWidth().testTag("save_memo_button"),
                    enabled = enabled && !state.isSaving && state.draft.isNotBlank()) {
                    Text(stringResource(R.string.memo_save))
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
                RequestError(issue, Modifier.testTag("memo_error"), uncertain = state.outcomeUnknown)
                Text(stringResource(R.string.memo_draft_kept), style = MaterialTheme.typography.bodySmall)
                if (state.outcomeUnknown) Text(stringResource(R.string.memo_save_unconfirmed),
                    style = MaterialTheme.typography.bodySmall)
            }
        } }
        state.lastSaved?.let { memo -> item(key = "save_receipt") {
            Column(Modifier.testTag("memo_saved"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.memo_saved), style = MaterialTheme.typography.titleMedium)
                Text(displayTimestamp(memo.createdAt), style = MaterialTheme.typography.bodySmall)
                SheetCopyStatus(memo.sheetStatus)
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
        if (state.isLoadingHistory) item(key = "history_loading") {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("memo_history_loading"))
        }
        if (state.memos.isNotEmpty()) item(key = "memo_selection") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.memo_selection_count, selectedMemos.size, MAX_CHAT_MEMOS),
                    modifier = Modifier.testTag("memo_selection_count"))
                Button(onClick = { keyboard?.hide(); onChatWithSelected() }, modifier = Modifier.fillMaxWidth().testTag("chat_with_memos_button"),
                    enabled = canImportToChat && selectedMemos.isNotEmpty() &&
                        selectedMemos.size <= MAX_CHAT_MEMOS && selectedCharacters <= MAX_MEMO_CONTEXT_CHARACTERS) {
                    Text(stringResource(R.string.memo_chat_selected))
                }
                Text(stringResource(R.string.memo_context_limit, MAX_MEMO_CONTEXT_CHARACTERS),
                    style = MaterialTheme.typography.bodySmall)
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
                Text(stringResource(R.string.memo_history_empty), modifier = Modifier.testTag("memo_history_empty"),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(state.memos, key = { "memo_" + it.id }) { memo ->
            val selected = memo.id in state.selectedMemoIds
            val canSelect = enabled && !state.isSaving && (selected || (selectedMemos.size < MAX_CHAT_MEMOS &&
                selectedCharacters + chatCharacterCount(memo.prompt) <= MAX_MEMO_CONTEXT_CHARACTERS))
            MemoHistoryCard(memo, selected, canSelect) { onToggleMemo(memo.id) }
        }
    }
    state.confirmation?.let { prompt ->
        AlertDialog(onDismissRequest = onCancelSave,
            title = { Text(stringResource(R.string.memo_confirm_title)) },
            text = { SelectionContainer {
                Text(prompt, Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()))
            } },
            confirmButton = { TextButton(onConfirmSave, modifier = Modifier.testTag("confirm_save_memo_button"),
                enabled = enabled && !state.isSaving) { Text(stringResource(R.string.memo_confirm)) } },
            dismissButton = { TextButton(onCancelSave) { Text(stringResource(R.string.memo_keep_editing)) } })
    }
}

@Composable
private fun MemoHistoryCard(memo: Memo, selected: Boolean, canSelect: Boolean, onToggle: () -> Unit) {
    var expanded by rememberSaveable(memo.id) { mutableStateOf(false) }
    var canExpand by rememberSaveable(memo.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("memo_${memo.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(selected, onCheckedChange = { onToggle() }, enabled = canSelect,
                    modifier = Modifier.testTag("select_memo_${memo.id}"))
                Text(displayTimestamp(memo.createdAt), style = MaterialTheme.typography.bodySmall)
            }
            SelectionContainer {
                Text(memo.prompt, maxLines = if (expanded) Int.MAX_VALUE else 6,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { layout -> if (!expanded) canExpand = layout.hasVisualOverflow })
            }
            if (canExpand || expanded) TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.memo_show_less else R.string.memo_show_more))
            }
            SheetCopyStatus(memo.sheetStatus)
            if (chatCharacterCount(memo.prompt) > MAX_MEMO_CONTEXT_CHARACTERS) {
                Text(stringResource(R.string.memo_too_long_for_chat), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SheetCopyStatus(status: SheetSaveStatus) {
    Text(stringResource(if (status == SheetSaveStatus.Saved) R.string.memo_sheet_saved else R.string.memo_sheet_unconfirmed),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
