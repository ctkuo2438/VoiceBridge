package com.zhenting.voicebridge.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhenting.voicebridge.R
import com.zhenting.voicebridge.data.model.MemoContext
import com.zhenting.voicebridge.ui.components.displayTimestamp

@Composable
fun MemoContextPreview(
    memos: List<MemoContext>,
    onRemove: ((String) -> Unit)?,
    onDismiss: () -> Unit
) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_attached_memos, memos.size)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(memos, key = { it.id }) { memo ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(displayTimestamp(memo.createdAt), style = MaterialTheme.typography.bodySmall)
                        SelectionContainer { Text(memo.prompt) }
                        if (onRemove != null) TextButton(onClick = { onRemove(memo.id) },
                            modifier = Modifier.testTag("remove_memo_${memo.id}")) {
                            Text(stringResource(R.string.chat_remove_memo))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_close)) } })
}
