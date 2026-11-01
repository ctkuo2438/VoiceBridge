package com.zhenting.voicebridge.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhenting.voicebridge.R
import com.zhenting.voicebridge.data.model.Conversation
import com.zhenting.voicebridge.data.model.MAX_CHAT_CHARACTERS
import com.zhenting.voicebridge.data.model.MessageRole
import com.zhenting.voicebridge.data.model.MemoSource
import com.zhenting.voicebridge.data.model.AnswerKind
import com.zhenting.voicebridge.data.model.SheetSaveStatus
import com.zhenting.voicebridge.data.model.chatCharacterCount
import com.zhenting.voicebridge.ui.components.RequestError
import com.zhenting.voicebridge.ui.components.AppSymbol
import com.zhenting.voicebridge.ui.components.EmptyState
import com.zhenting.voicebridge.ui.components.SymbolIcon
import com.zhenting.voicebridge.ui.components.displayTimestamp
import com.zhenting.voicebridge.ui.tts.ReadAloudControl
import com.zhenting.voicebridge.ui.tts.TtsUiState

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
    enabled: Boolean = true,
    ttsState: TtsUiState = TtsUiState(),
    onReadAloud: (String, String) -> Unit = { _, _ -> },
    onStopSpeech: () -> Unit = {}
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showMemoContext by rememberSaveable { mutableStateOf(false) }
    var selectedSource by remember { mutableStateOf<Pair<Long, MemoSource>?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.conversationId) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex)
    }
    val count = chatCharacterCount(state.draft.trim())
    val newChatLabel = stringResource(R.string.chat_new)
    val historyLabel = stringResource(R.string.chat_history)
    val sendLabel = stringResource(R.string.chat_send)
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(state.title ?: newChatLabel, maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).testTag("conversation_title"))
            IconButton(onClick = onNewChat, enabled = enabled && !state.isSending,
                modifier = Modifier.testTag("new_chat_button").semantics { contentDescription = newChatLabel }) {
                SymbolIcon(AppSymbol.Plus)
            }
            IconButton(onClick = { showHistory = true; onRefreshHistory() },
                enabled = enabled && !state.isSending && !state.isLoadingMessages,
                modifier = Modifier.testTag("chat_history_button").semantics { contentDescription = historyLabel }) {
                SymbolIcon(AppSymbol.History)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.memoContext.isNotEmpty()) {
                TextButton(onClick = { keyboard?.hide(); showMemoContext = true }, modifier = Modifier.testTag("attached_memos_button")) {
                    Text(stringResource(R.string.chat_attached_memos, state.memoContext.size))
                }
            }
            TextButton(onClick = { keyboard?.hide(); onChooseMemos() }, enabled = enabled && !state.isSending && !state.isLoadingMessages,
                modifier = Modifier.testTag("choose_memos_button")) {
                SymbolIcon(AppSymbol.Note, Modifier.padding(end = 6.dp).size(18.dp))
                Text(stringResource(R.string.chat_add_memos))
            }
        }
        if (state.isLoadingMessages) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("chat_messages"), state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.messages.isEmpty() && !state.isLoadingMessages) item(key = "empty") {
                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(AppSymbol.Chat, stringResource(R.string.chat_empty_title),
                        stringResource(R.string.chat_empty_description))
                }
            }
            items(state.messages, key = { it.id }) { message ->
                val fromUser = message.role == MessageRole.User
                Column(Modifier.fillMaxWidth(), horizontalAlignment =
                    if (fromUser) Alignment.End else Alignment.Start) {
                    Card(Modifier.fillMaxWidth(if (fromUser) 0.88f else 0.96f),
                        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp,
                            bottomEnd = if (fromUser) 6.dp else 22.dp,
                            bottomStart = if (fromUser) 22.dp else 6.dp),
                        border = if (fromUser) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors = CardDefaults.cardColors(
                            containerColor = if (fromUser) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                            contentColor = if (fromUser) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(if (fromUser) R.string.chat_you else R.string.chat_assistant),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (fromUser) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.primary)
                            SelectionContainer { Text(message.content, style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.testTag("message_${message.id}")) }
                            val sources = message.sections.flatMap { it.sources }.distinctBy { it.memoId to it.paragraphId }
                            if (sources.isNotEmpty()) {
                                Text(stringResource(R.string.chat_sources), style = MaterialTheme.typography.labelMedium)
                                for (source in sources) TextButton(onClick = { keyboard?.hide(); selectedSource = message.id to source },
                                    modifier = Modifier.testTag("source_${message.id}_${source.memoId}_${source.paragraphId}")) {
                                    Text(stringResource(R.string.chat_source_link, source.memoTitle, source.paragraphId.drop(1)))
                                }
                            }
                            if (message.sections.any { it.kind == AnswerKind.General }) Text(
                                stringResource(R.string.chat_general_explanation), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (message.sections.any { it.kind == AnswerKind.NotFound }) Text(
                                stringResource(R.string.chat_not_in_notes), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (message.role == MessageRole.Assistant) ReadAloudControl(
                                "chat:${state.conversationId.orEmpty()}:${message.id}", message.content,
                                ttsState, onReadAloud, onStopSpeech,
                                enabled = enabled && !state.isSending && !state.isLoadingMessages)
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
                label = { Text(stringResource(R.string.chat_input_label)) },
                supportingText = if (count > 3000) { { Text("$count / $MAX_CHAT_CHARACTERS") } } else null,
                isError = count > MAX_CHAT_CHARACTERS)
            FilledIconButton(onClick = { keyboard?.hide(); onSend() }, enabled = enabled && !state.isSending && !state.isLoadingMessages &&
                !state.requiresReload && count in 1..MAX_CHAT_CHARACTERS,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.size(52.dp).testTag("send_message_button").semantics { contentDescription = sendLabel }) {
                SymbolIcon(AppSymbol.Send, color = if (enabled && !state.isSending && !state.isLoadingMessages &&
                    !state.requiresReload && count in 1..MAX_CHAT_CHARACTERS) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            }
        }
    }
    selectedSource?.let { (messageId, source) ->
        val message = state.messages.find { it.id == messageId }
        val memo = state.memoContext.find { it.id == source.memoId }
        if (message != null && memo != null) MemoSourcePreview(source,
            message.sections.filter { section -> section.sources.any { it.memoId == source.memoId && it.paragraphId == source.paragraphId } }
                .joinToString("\n\n") { it.text }, memo, onDismiss = { selectedSource = null })
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
