package com.zhenting.voicebridge.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhenting.voicebridge.R
import com.zhenting.voicebridge.data.model.Conversation
import com.zhenting.voicebridge.data.model.MAX_CHAT_CHARACTERS
import com.zhenting.voicebridge.data.model.MessageRole
import com.zhenting.voicebridge.data.model.SheetSaveStatus
import com.zhenting.voicebridge.data.model.chatCharacterCount
import com.zhenting.voicebridge.ui.components.RequestError
import com.zhenting.voicebridge.ui.components.displayTimestamp

@Composable
fun ChatScreen(
    state: ChatUiState,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onNewChat: () -> Unit,
    onRefreshHistory: () -> Unit,
    onSelectConversation: (Conversation) -> Unit,
    onReload: () -> Unit,
    onChooseMemos: () -> Unit,
    onRemoveMemo: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showMemoContext by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.conversationId) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex)
    }
    val count = chatCharacterCount(state.draft.trim())
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onNewChat, enabled = enabled && !state.isSending,
                modifier = Modifier.testTag("new_chat_button")) { Text(stringResource(R.string.chat_new)) }
            TextButton(onClick = { showHistory = true; onRefreshHistory() },
                enabled = enabled && !state.isSending && !state.isLoadingMessages,
                modifier = Modifier.testTag("chat_history_button")) { Text(stringResource(R.string.chat_history)) }
        }
        Text(state.title ?: stringResource(R.string.chat_new), maxLines = 1,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("conversation_title"))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            if (state.memoContext.isNotEmpty()) {
                TextButton(onClick = { keyboard?.hide(); showMemoContext = true }, modifier = Modifier.testTag("attached_memos_button")) {
                    Text(stringResource(R.string.chat_attached_memos, state.memoContext.size))
                }
            }
            TextButton(onClick = { keyboard?.hide(); onChooseMemos() }, enabled = enabled && !state.isSending && !state.isLoadingMessages,
                modifier = Modifier.testTag("choose_memos_button")) {
                Text(stringResource(R.string.chat_add_memos))
            }
        }
        if (state.isLoadingMessages) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.messages.isEmpty() && !state.isLoadingMessages) {
            Text(stringResource(R.string.chat_empty), style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("chat_messages"), state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.messages, key = { it.id }) { message ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment =
                    if (message.role == MessageRole.User) Alignment.End else Alignment.Start) {
                    Card(Modifier.fillMaxWidth(0.92f), colors = CardDefaults.cardColors(
                        containerColor = if (message.role == MessageRole.User) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(if (message.role == MessageRole.User) R.string.chat_you else R.string.chat_assistant),
                                style = MaterialTheme.typography.labelMedium)
                            SelectionContainer { Text(message.content, modifier = Modifier.testTag("message_${message.id}")) }
                        }
                    }
                }
            }
        }
        if (state.isSending) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.chat_thinking), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("chat_sending"))
        }
        state.sheetStatus?.let {
            Text(stringResource(if (it == SheetSaveStatus.Saved) R.string.chat_sheet_saved else R.string.chat_sheet_unconfirmed),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("chat_sheet_status"))
        }
        state.error?.let {
            RequestError(it, Modifier.testTag("chat_error"), state.requiresReload)
            if (state.conversationId != null) TextButton(onClick = onReload,
                enabled = enabled && !state.isSending && !state.isLoadingMessages) {
                Text(stringResource(R.string.chat_reload))
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(state.draft, onDraftChanged,
                modifier = Modifier.weight(1f).testTag("chat_input"),
                enabled = enabled && !state.isSending, maxLines = 4,
                label = { Text(stringResource(R.string.chat_input_label)) },
                supportingText = { Text("$count / $MAX_CHAT_CHARACTERS") },
                isError = count > MAX_CHAT_CHARACTERS)
            Button(onClick = { keyboard?.hide(); onSend() }, enabled = enabled && !state.isSending && !state.isLoadingMessages &&
                !state.requiresReload && count in 1..MAX_CHAT_CHARACTERS,
                modifier = Modifier.testTag("send_message_button")) { Text(stringResource(R.string.chat_send)) }
        }
    }
    if (showMemoContext && state.memoContext.isNotEmpty()) MemoContextPreview(
        state.memoContext,
        onRemove = if (state.conversationId == null && !state.isSending) { id ->
            onRemoveMemo(id)
            if (state.memoContext.size == 1) showMemoContext = false
        } else null,
        onDismiss = { showMemoContext = false })
    if (showHistory) AlertDialog(
        onDismissRequest = { showHistory = false },
        title = { Text(stringResource(R.string.chat_history)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onRefreshHistory, enabled = !state.isLoadingConversations) {
                    Text(stringResource(R.string.chat_refresh_history))
                }
                if (state.isLoadingConversations) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.conversations.isEmpty() && !state.isLoadingConversations) {
                    Text(stringResource(R.string.chat_no_history))
                }
                state.historyError?.let { RequestError(it) }
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.conversations, key = { it.id }) { conversation ->
                        Column(Modifier.fillMaxWidth().clickable {
                            showHistory = false
                            onSelectConversation(conversation)
                        }.padding(vertical = 12.dp).testTag("conversation_${conversation.id}")) {
                            Text(conversation.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(displayTimestamp(conversation.updatedAt), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showHistory = false }) { Text(stringResource(R.string.action_close)) } }
    )
}
