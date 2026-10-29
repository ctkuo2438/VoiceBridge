package com.zhenting.voicebridge.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhenting.voicebridge.R
import com.zhenting.voicebridge.data.repository.ChatRepository
import com.zhenting.voicebridge.data.repository.MemoRepository
import com.zhenting.voicebridge.data.repository.SpeechRepository
import com.zhenting.voicebridge.data.audio.AudioRecorder
import com.zhenting.voicebridge.ui.auth.AuthUiState
import com.zhenting.voicebridge.ui.auth.messageResource
import com.zhenting.voicebridge.ui.chat.ChatScreen
import com.zhenting.voicebridge.ui.chat.ChatViewModel
import com.zhenting.voicebridge.ui.memo.MemoScreen
import com.zhenting.voicebridge.ui.memo.MemoViewModel
import com.zhenting.voicebridge.ui.speech.SpeechInput
import com.zhenting.voicebridge.ui.speech.SpeechTarget
import com.zhenting.voicebridge.ui.speech.SpeechViewModel
import com.zhenting.voicebridge.ui.memo.MemoPickerDialog
import com.zhenting.voicebridge.data.model.Memo
import com.zhenting.voicebridge.data.model.asContext

enum class HomeMode { Chat, Memo }

@Composable
fun HomeRoute(
    workspaceViewModel: WorkspaceViewModel,
    authState: AuthUiState,
    createChatRepository: (String) -> ChatRepository,
    createMemoRepository: (String) -> MemoRepository,
    onSignOut: () -> Unit,
    onDismissAuthError: () -> Unit,
    modifier: Modifier = Modifier,
    createSpeechRepository: (String) -> SpeechRepository,
    createAudioRecorder: () -> AudioRecorder
) {
    val workspace by workspaceViewModel.workspace.collectAsStateWithLifecycle()
    val owner = workspace
    if (owner == null || owner.userId != authState.user?.id) {
        LinearProgressIndicator(modifier.fillMaxWidth())
        return
    }
    val chatRepository = remember(owner) { createChatRepository(owner.userId) }
    val memoRepository = remember(owner) { createMemoRepository(owner.userId) }
    val speechRepository = remember(owner) { createSpeechRepository(owner.userId) }
    val chat: ChatViewModel = viewModel(viewModelStoreOwner = owner,
        factory = ChatViewModel.factory(chatRepository))
    val memo: MemoViewModel = viewModel(viewModelStoreOwner = owner,
        factory = MemoViewModel.factory(memoRepository))
    val speech: SpeechViewModel = viewModel(viewModelStoreOwner = owner,
        factory = SpeechViewModel.factory(createAudioRecorder, speechRepository))
    val chatState by chat.uiState.collectAsStateWithLifecycle()
    val memoState by memo.uiState.collectAsStateWithLifecycle()
    val speechState by speech.uiState.collectAsStateWithLifecycle()
    var mode by rememberSaveable(owner.userId) { mutableStateOf(HomeMode.Chat) }
    var showMemoPicker by rememberSaveable(owner.userId) { mutableStateOf(false) }
    val canImportMemos = !authState.isLoading && !speechState.isBusy && !memoState.isSaving &&
        !chatState.isSending && !chatState.isLoadingMessages
    val importMemos: (List<Memo>) -> Unit = { selected ->
        if (canImportMemos && chat.startWithMemos(selected.map { it.asContext() })) {
            mode = HomeMode.Chat
            showMemoPicker = false
            memo.clearSelection()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { speech.stopForBackground() }
    HomeScreen(authState, { speech.cancel(); onSignOut() }, onDismissAuthError, modifier,
        mode = mode, onModeChange = { mode = it },
        voiceBusy = speechState.isBusy,
        voiceControls = { mode ->
            val target = if (mode == HomeMode.Chat) SpeechTarget.Chat else SpeechTarget.Memo
            val canRecord = !authState.isLoading && if (mode == HomeMode.Chat) {
                !chatState.isSending && !chatState.isLoadingMessages
            } else !memoState.isSaving && memoState.confirmation == null
            SpeechInput(speechState, target, speech::start, speech::stopAndTranscribe,
                speech::cancel, speech::permissionDenied, speech::editTranscript,
                onUseTranscript = {
                    speech.acceptTranscript { destination, text ->
                        when (destination) {
                            SpeechTarget.Chat -> chat.editDraft(appendTranscript(chat.uiState.value.draft, text))
                            SpeechTarget.Memo -> memo.editDraft(appendTranscript(memo.uiState.value.draft, text))
                        }
                    }
                }, enabled = canRecord)
        }) { mode ->
        when (mode) {
            HomeMode.Chat -> ChatScreen(chatState, chat::editDraft, chat::send,
                chat::newConversation, chat::refreshConversations, chat::selectConversation,
                chat::reloadConversation,
                onChooseMemos = { memo.refreshHistory(); showMemoPicker = true },
                onRemoveMemo = chat::removeMemo,
                enabled = !authState.isLoading && !speechState.isBusy)
            HomeMode.Memo -> MemoScreen(memoState, memo::editDraft, memo::requestSave,
                memo::saveConfirmed, memo::cancelConfirmation, memo::refreshHistory,
                onToggleMemo = memo::toggleMemo,
                onChatWithSelected = { importMemos(memoState.memos.filter { it.id in memoState.selectedMemoIds }) },
                canImportToChat = canImportMemos,
                enabled = !authState.isLoading && !speechState.isBusy)
        }
    }
    if (showMemoPicker) MemoPickerDialog(memoState,
        initialMemoIds = if (chatState.conversationId == null) chatState.memoContext.map { it.id } else emptyList(),
        onRefresh = memo::refreshHistory, onApply = importMemos,
        onDismiss = { showMemoPicker = false }, enabled = canImportMemos)
}

@Composable
fun HomeScreen(
    authState: AuthUiState,
    onSignOut: () -> Unit,
    onDismissAuthError: () -> Unit,
    modifier: Modifier = Modifier,
    mode: HomeMode,
    onModeChange: (HomeMode) -> Unit,
    voiceBusy: Boolean = false,
    voiceControls: @Composable (HomeMode) -> Unit = {},
    content: @Composable (HomeMode) -> Unit
) {
    val user = authState.user ?: return
    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                Text(user.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("home_user"))
            }
            TextButton(onSignOut, enabled = !authState.isLoading,
                modifier = Modifier.testTag("home_sign_out_button")) { Text(stringResource(R.string.auth_sign_out)) }
        }
        if (authState.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        authState.error?.let {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(it.messageResource()), color = MaterialTheme.colorScheme.error)
                TextButton(onDismissAuthError) { Text(stringResource(R.string.auth_dismiss)) }
            }
        }
        TabRow(selectedTabIndex = mode.ordinal) {
            Tab(selected = mode == HomeMode.Chat, onClick = { onModeChange(HomeMode.Chat) },
                enabled = !authState.isLoading && !voiceBusy, modifier = Modifier.testTag("chat_tab"),
                text = { Text(stringResource(R.string.mode_chat)) })
            Tab(selected = mode == HomeMode.Memo, onClick = { onModeChange(HomeMode.Memo) },
                enabled = !authState.isLoading && !voiceBusy, modifier = Modifier.testTag("memo_tab"),
                text = { Text(stringResource(R.string.mode_memo)) })
        }
        voiceControls(mode)
        Box(Modifier.weight(1f).fillMaxWidth()) { content(mode) }
    }
}

private fun appendTranscript(draft: String, text: String): String =
    if (draft.isBlank()) text else draft.trimEnd() + "\n" + text
