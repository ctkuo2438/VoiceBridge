package com.zhenting.voicebridge.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhenting.voicebridge.R
import com.zhenting.voicebridge.data.model.MemoContext
import com.zhenting.voicebridge.data.model.MemoSource

@Composable
fun MemoSourcePreview(source: MemoSource, answerText: String, memo: MemoContext, onDismiss: () -> Unit) {
    var showFullNote by remember(source) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(source.memoTitle, modifier = Modifier.testTag("source_note_title")) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.chat_source_snapshot), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.chat_source_answer), style = MaterialTheme.typography.titleSmall)
                SelectionContainer { Text(answerText, modifier = Modifier.testTag("source_answer_excerpt")) }
                Text(stringResource(R.string.chat_source_paragraph, source.paragraphId.drop(1)),
                    style = MaterialTheme.typography.titleSmall)
                SelectionContainer { Text(source.quote, modifier = Modifier.testTag("source_original_quote")) }
                TextButton(onClick = { showFullNote = !showFullNote }, modifier = Modifier.testTag("source_full_note_button")) {
                    Text(stringResource(if (showFullNote) R.string.chat_source_hide_note else R.string.chat_source_full_note))
                }
                if (showFullNote) SelectionContainer {
                    Text(memo.prompt, modifier = Modifier.testTag("source_full_note"))
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_close)) } })
}
