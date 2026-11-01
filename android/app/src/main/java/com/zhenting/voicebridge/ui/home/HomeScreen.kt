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
import androidx.compose.material3.AlertDialog
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
import com.zhenting.voicebridge.ui.speech.SpeechPhase
import com.zhenting.voicebridge.ui.speech.SpeechViewModel
import com.zhenting.voicebridge.ui.memo.MemoPickerDialog
import com.zhenting.voicebridge.data.model.Memo
import com.zhenting.voicebridge.data.model.MemoAction
import com.zhenting.voicebridge.data.local.LocalSaveStatus
import com.zhenting.voicebridge.data.local.DraftDestination
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
    createAudioRecorder: (String) -> AudioRecorder,
    createTtsRepository: (String) -> TtsRepository,
    createAudioPlayer: () -> AudioPlayer
) {
    val workspace by workspaceViewModel.workspace.collectAsStateWithLifecycle()
    val storageError by workspaceViewModel.storageError.collectAsStateWithLifecycle()
    val owner = workspace
    if (owner == null || owner.userId != authState.user?.id) {
        if (storageError) Column(modifier.padding(24.dp)) {
            Text(stringResource(R.string.draft_restore_failed))
            TextButton(workspaceViewModel::retryRestore) { Text(stringResource(R.string.action_retry)) }
            TextButton(onSignOut) { Text(stringResource(R.string.auth_sign_out)) }
        } else LinearProgressIndicator(modifier.fillMaxWidth())
        return
    }
    val chatRepository = remember(owner) { createChatRepository(owner.userId) }
    val memoRepository = remember(owner) { createMemoRepository(owner.userId) }
    val speechRepository = remember(owner) { createSpeechRepository(owner.userId) }
    val ttsRepository = remember(owner) { createTtsRepository(owner.userId) }
    val chat: ChatViewModel = viewModel(viewModelStoreOwner = owner,
        factory = ChatViewModel.factory(chatRepository, owner.recovery))
    val memo: MemoViewModel = viewModel(viewModelStoreOwner = owner,
        factory = MemoViewModel.factory(memoRepository, owner.recovery))
    val speech: SpeechViewModel = viewModel(viewModelStoreOwner = owner,
        factory = SpeechViewModel.factory({ createAudioRecorder(owner.userId) }, speechRepository, owner.recovery))
    val tts: TtsViewModel = viewModel(viewModelStoreOwner = owner,
        factory = TtsViewModel.factory(ttsRepository, createAudioPlayer))
    val chatState by chat.uiState.collectAsStateWithLifecycle()
    val memoState by memo.uiState.collectAsStateWithLifecycle()
    val speechState by speech.uiState.collectAsStateWithLifecycle()
    val ttsState by tts.uiState.collectAsStateWithLifecycle()
    val localSave by owner.recovery.saveProgress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable(owner.userId) { mutableStateOf(
        if ((owner.recovery.snapshot.speech?.target ?: owner.recovery.snapshot.destination) == DraftDestination.Memo)
            HomeMode.Memo else HomeMode.Chat) }
    var showMemoPicker by rememberSaveable(owner.userId) { mutableStateOf(false) }
    var pendingMemoAction by remember(owner) { mutableStateOf<Pair<Memo, MemoAction>?>(null) }
    val canImportMemos = !authState.isLoading && !speechState.isBusy && !memoState.isSaving && memoState.editing == null &&
        !chatState.isSending && !chatState.isLoadingMessages
    val importMemos: (List<Memo>) -> Unit = { selected ->
        if (canImportMemos && chat.startWithMemos(selected.map { it.asContext() })) {
            tts.stop()
            mode = HomeMode.Chat
            showMemoPicker = false
            memo.clearSelection()
        }
    }
    val runMemoAction: (Memo, MemoAction, Boolean) -> Unit = { selected, action, replace ->
        if (canImportMemos && chat.startMemoAction(selected.asContext(), action, replace)) {
            tts.stop()
            mode = HomeMode.Chat
            memo.clearSelection()
            pendingMemoAction = null
        }
    }
    DisposableEffect(tts) { onDispose { tts.stop() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { speech.stopForBackground(); tts.stop() }
    HomeScreen(authState, { speech.stopForBackground(); tts.stop(); onSignOut() }, onDismissAuthError, modifier,
        mode = mode, onModeChange = { selected ->
            tts.stop(); mode = selected
            owner.recovery.update { it.copy(destination = if (selected == HomeMode.Memo) DraftDestination.Memo else DraftDestination.Chat) }
        },
        voiceBusy = speechState.isBusy,
        voiceControls = { visibleMode ->
            val target = if (visibleMode == HomeMode.Chat) SpeechTarget.Chat else SpeechTarget.Memo
            val destination = if (speechState.phase == SpeechPhase.Idle) target
                else speechState.target ?: target
            val canRecord = !authState.isLoading && if (destination == SpeechTarget.Chat) {
                !chatState.isSending && !chatState.isLoadingMessages
            } else !memoState.isSaving && memoState.confirmation == null && memoState.editing == null
            SpeechInput(speechState, target, { tts.stop(); speech.start(it) }, speech::stopAndTranscribe,
                speech::cancel, speech::permissionDenied, speech::editTranscript,
                onUseTranscript = {
                    val destinationReady = when (speech.uiState.value.target) {
                        SpeechTarget.Chat -> !chat.uiState.value.isSending && !chat.uiState.value.isLoadingMessages
                        SpeechTarget.Memo -> !memo.uiState.value.isSaving && memo.uiState.value.editing == null
                        null -> false
                    }
                    if (destinationReady) {
                        speech.acceptTranscript { acceptedTarget, text ->
                            when (acceptedTarget) {
                                SpeechTarget.Chat -> { chat.editDraft(text); mode = HomeMode.Chat }
                                SpeechTarget.Memo -> { memo.editDraft(text); mode = HomeMode.Memo }
                            }
                        }
                    }
                }, enabled = canRecord, onRetry = { tts.stop(); speech.retry() })
            if (localSave.status != LocalSaveStatus.Saved || owner.recovery.snapshot.memo.text.isNotEmpty() ||
                owner.recovery.snapshot.memo.title.isNotEmpty() || owner.recovery.snapshot.memoEdit != null ||
                owner.recovery.snapshot.chat.text.isNotEmpty() || owner.recovery.snapshot.speech != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(when (localSave.status) {
                        LocalSaveStatus.Saving -> R.string.draft_saving_local
                        LocalSaveStatus.Saved -> R.string.draft_saved_local
                        LocalSaveStatus.Failed -> R.string.draft_storage_failed
                    }), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).testTag("draft_local_status"),
                        color = if (localSave.status == LocalSaveStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (localSave.status == LocalSaveStatus.Failed) TextButton(onClick = { scope.launch { owner.recovery.flush() } }) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
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
                ttsState = ttsState, onReadAloud = tts::speak, onStopSpeech = tts::stop,
                onTitleChanged = memo::editDraftTitle, onSearchChanged = memo::search,
                onEditMemo = { tts.stop(); memo.startEditing(it) }, onEditTitleChanged = memo::editSavedTitle,
                onEditTextChanged = memo::editSavedText, onSaveEdit = memo::saveEditedMemo,
                onCancelEdit = memo::cancelEditing, onReloadEdit = memo::reloadEditedMemo,
                onMemoAction = { selected, action ->
                    if (action != MemoAction.Ask && chat.uiState.value.draft.isNotBlank()) pendingMemoAction = selected to action
                    else runMemoAction(selected, action, false)
                })
        }
    }
    if (showMemoPicker) MemoPickerDialog(memoState,
        initialMemoIds = if (chatState.conversationId == null) chatState.memoContext.map { it.id } else emptyList(),
        onRefresh = memo::refreshHistory, onApply = importMemos,
        onDismiss = { showMemoPicker = false }, enabled = canImportMemos)
    pendingMemoAction?.let { (selected, action) ->
        AlertDialog(onDismissRequest = { pendingMemoAction = null },
            title = { Text(stringResource(R.string.memo_action_replace_title)) },
            text = { Text(stringResource(R.string.memo_action_replace_description)) },
            confirmButton = { TextButton(onClick = { runMemoAction(selected, action, true) }, enabled = canImportMemos,
                modifier = Modifier.testTag("confirm_memo_action")) { Text(stringResource(R.string.memo_action_continue)) } },
            dismissButton = { TextButton(onClick = { pendingMemoAction = null }, modifier = Modifier.testTag("keep_chat_draft")) {
                Text(stringResource(R.string.memo_action_keep_draft))
            } })
    }
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
