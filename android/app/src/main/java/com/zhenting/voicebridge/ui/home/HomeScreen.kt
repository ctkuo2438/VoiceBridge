package com.zhenting.voicebridge.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.Surface
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
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
import com.zhenting.voicebridge.data.audio.AudioPlayer
import com.zhenting.voicebridge.data.repository.TtsRepository
import com.zhenting.voicebridge.ui.tts.TtsViewModel
import com.zhenting.voicebridge.ui.components.AppSymbol
import com.zhenting.voicebridge.ui.components.SymbolIcon
import com.zhenting.voicebridge.ui.components.BrandMark
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
    createAudioRecorder: () -> AudioRecorder,
    createTtsRepository: (String) -> TtsRepository,
    createAudioPlayer: () -> AudioPlayer
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
    val ttsRepository = remember(owner) { createTtsRepository(owner.userId) }
    val chat: ChatViewModel = viewModel(viewModelStoreOwner = owner,
        factory = ChatViewModel.factory(chatRepository))
    val memo: MemoViewModel = viewModel(viewModelStoreOwner = owner,
        factory = MemoViewModel.factory(memoRepository))
    val speech: SpeechViewModel = viewModel(viewModelStoreOwner = owner,
        factory = SpeechViewModel.factory(createAudioRecorder, speechRepository))
    val tts: TtsViewModel = viewModel(viewModelStoreOwner = owner,
        factory = TtsViewModel.factory(ttsRepository, createAudioPlayer))
    val chatState by chat.uiState.collectAsStateWithLifecycle()
    val memoState by memo.uiState.collectAsStateWithLifecycle()
    val speechState by speech.uiState.collectAsStateWithLifecycle()
    val ttsState by tts.uiState.collectAsStateWithLifecycle()
    var mode by rememberSaveable(owner.userId) { mutableStateOf(HomeMode.Chat) }
    var showMemoPicker by rememberSaveable(owner.userId) { mutableStateOf(false) }
    val canImportMemos = !authState.isLoading && !speechState.isBusy && !memoState.isSaving &&
        !chatState.isSending && !chatState.isLoadingMessages
    val importMemos: (List<Memo>) -> Unit = { selected ->
        if (canImportMemos && chat.startWithMemos(selected.map { it.asContext() })) {
            tts.stop()
            mode = HomeMode.Chat
            showMemoPicker = false
            memo.clearSelection()
        }
    }
    DisposableEffect(tts) { onDispose { tts.stop() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { speech.stopForBackground(); tts.stop() }
    HomeScreen(authState, { speech.cancel(); tts.stop(); onSignOut() }, onDismissAuthError, modifier,
        mode = mode, onModeChange = { tts.stop(); mode = it },
        voiceBusy = speechState.isBusy,
        voiceControls = { mode ->
            val target = if (mode == HomeMode.Chat) SpeechTarget.Chat else SpeechTarget.Memo
            val canRecord = !authState.isLoading && if (mode == HomeMode.Chat) {
                !chatState.isSending && !chatState.isLoadingMessages
            } else !memoState.isSaving && memoState.confirmation == null
            SpeechInput(speechState, target, { tts.stop(); speech.start(it) }, speech::stopAndTranscribe,
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
            HomeMode.Chat -> ChatScreen(chatState, chat::editDraft, { tts.stop(); chat.send() },
                { tts.stop(); chat.newConversation() }, chat::refreshConversations,
                { tts.stop(); chat.selectConversation(it) }, { tts.stop(); chat.reloadConversation() },
                onChooseMemos = { tts.stop(); memo.refreshHistory(); showMemoPicker = true },
                onRemoveMemo = chat::removeMemo,
                enabled = !authState.isLoading && !speechState.isBusy,
                ttsState = ttsState, onReadAloud = tts::speak, onStopSpeech = tts::stop)
            HomeMode.Memo -> MemoScreen(memoState, memo::editDraft, memo::requestSave,
                memo::saveConfirmed, memo::cancelConfirmation, memo::refreshHistory,
                onToggleMemo = memo::toggleMemo,
                onChatWithSelected = { importMemos(memoState.memos.filter { it.id in memoState.selectedMemoIds }) },
                canImportToChat = canImportMemos,
                enabled = !authState.isLoading && !speechState.isBusy,
                ttsState = ttsState, onReadAloud = tts::speak, onStopSpeech = tts::stop)
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BrandMark(38.dp)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                Text(user.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("home_user"))
            }
            TextButton(onSignOut, enabled = !authState.isLoading,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.testTag("home_sign_out_button")) { Text(stringResource(R.string.auth_sign_out)) }
        }
        if (authState.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        authState.error?.let {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(it.messageResource()), color = MaterialTheme.colorScheme.error)
                TextButton(onDismissAuthError) { Text(stringResource(R.string.auth_dismiss)) }
            }
        }
        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (option in HomeMode.entries) {
                    val selected = mode == option
                    val color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    Tab(selected = selected, onClick = { onModeChange(option) },
                        enabled = !authState.isLoading && !voiceBusy,
                        selectedContentColor = color, unselectedContentColor = color,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer)
                            .testTag(if (option == HomeMode.Chat) "chat_tab" else "memo_tab"),
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SymbolIcon(if (option == HomeMode.Chat) AppSymbol.Chat else AppSymbol.Note, color = color)
                                Text(stringResource(if (option == HomeMode.Chat) R.string.mode_chat else R.string.mode_memo),
                                    style = MaterialTheme.typography.labelLarge)
                            }
                        })
                }
            }
        }
        voiceControls(mode)
        Box(Modifier.weight(1f).fillMaxWidth()) { content(mode) }
    }
}

private fun appendTranscript(draft: String, text: String): String =
    if (draft.isBlank()) text else draft.trimEnd() + "\n" + text
