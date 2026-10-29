package com.zhenting.voicebridge.ui.memo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhenting.voicebridge.R
import com.zhenting.voicebridge.data.model.MAX_CHAT_MEMOS
import com.zhenting.voicebridge.data.model.MAX_MEMO_CONTEXT_CHARACTERS
import com.zhenting.voicebridge.data.model.Memo
import com.zhenting.voicebridge.data.model.asContext
import com.zhenting.voicebridge.data.model.chatCharacterCount
import com.zhenting.voicebridge.data.model.memoContextSize
import com.zhenting.voicebridge.ui.components.RequestError
import com.zhenting.voicebridge.ui.components.displayTimestamp

@Composable
fun MemoPickerDialog(
    state: MemoUiState,
    initialMemoIds: List<String>,
    onRefresh: () -> Unit,
    onApply: (List<Memo>) -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean
) {
    var selectedIds by rememberSaveable(initialMemoIds.joinToString()) { mutableStateOf(initialMemoIds) }
    val selected = state.memos.filter { it.id in selectedIds }
    val characters = memoContextSize(selected.map { it.asContext() })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memo_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.memo_picker_description))
                Text(stringResource(R.string.memo_selection_count, selected.size, MAX_CHAT_MEMOS),
                    modifier = Modifier.testTag("memo_picker_count"))
                TextButton(onRefresh, enabled = enabled && !state.isLoadingHistory) {
                    Text(stringResource(R.string.memo_refresh))
                }
                if (state.isLoadingHistory) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.historyError?.let { RequestError(it) }
                if (state.memos.isEmpty() && !state.isLoadingHistory && state.historyError == null) {
                    Text(stringResource(R.string.memo_picker_empty))
                }
                LazyColumn(Modifier.heightIn(max = 320.dp).testTag("memo_picker_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.memos, key = { it.id }) { memo ->
                        val checked = memo.id in selectedIds
                        val canSelect = enabled && (checked || (selected.size < MAX_CHAT_MEMOS &&
                            characters + chatCharacterCount(memo.prompt) <= MAX_MEMO_CONTEXT_CHARACTERS))
                        Row(Modifier.fillMaxWidth().testTag("pick_memo_${memo.id}")
                            .toggleable(value = checked, enabled = canSelect, role = Role.Checkbox,
                                onValueChange = {
                                    selectedIds = if (memo.id in selectedIds) selectedIds.filterNot { it == memo.id }
                                        else selectedIds + memo.id
                                }).padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                            Checkbox(checked, onCheckedChange = null, enabled = canSelect)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(memo.prompt, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text(displayTimestamp(memo.createdAt), style = MaterialTheme.typography.bodySmall)
                                if (chatCharacterCount(memo.prompt) > MAX_MEMO_CONTEXT_CHARACTERS) {
                                    Text(stringResource(R.string.memo_too_long_for_chat), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                Text(stringResource(R.string.memo_context_limit, MAX_MEMO_CONTEXT_CHARACTERS),
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(selected) },
                enabled = enabled && !state.isLoadingHistory && selected.isNotEmpty() &&
                    selected.size <= MAX_CHAT_MEMOS && characters <= MAX_MEMO_CONTEXT_CHARACTERS,
                modifier = Modifier.testTag("apply_memos_button")) {
                Text(stringResource(R.string.memo_start_chat))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
