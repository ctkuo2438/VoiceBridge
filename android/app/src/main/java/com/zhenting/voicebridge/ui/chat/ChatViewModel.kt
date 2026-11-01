package com.zhenting.voicebridge.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhenting.voicebridge.data.model.Conversation
import com.zhenting.voicebridge.data.model.MAX_CHAT_CHARACTERS
import com.zhenting.voicebridge.data.model.chatCharacterCount
import com.zhenting.voicebridge.data.model.MemoContext
import com.zhenting.voicebridge.data.model.MemoAction
import com.zhenting.voicebridge.data.model.MAX_CHAT_MEMOS
import com.zhenting.voicebridge.data.model.MAX_MEMO_CONTEXT_CHARACTERS
import com.zhenting.voicebridge.data.model.memoContextSize
import com.zhenting.voicebridge.data.remote.ApiException
import com.zhenting.voicebridge.data.remote.ApiIssue
import com.zhenting.voicebridge.data.repository.ChatRepository
import com.zhenting.voicebridge.data.local.RecoveryStore
import com.zhenting.voicebridge.data.local.ChatDraft
import com.zhenting.voicebridge.data.local.DraftDestination
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(private val repository: ChatRepository, private val recovery: RecoveryStore = RecoveryStore()) : ViewModel() {
    private val _uiState = MutableStateFlow(recovery.snapshot.chat.let {
        ChatUiState(draft = it.text, conversationId = it.conversationId, title = it.title,
            memoContext = it.memoContext, requiresReload = it.requiresReload, memoAction = it.memoAction,
            error = if (it.requiresReload) ApiIssue.SaveUnconfirmed else null)
    })
    val uiState = _uiState.asStateFlow()
    private var historyJob: Job? = null
    private var messagesJob: Job? = null
    private var historyVersion = 0
    private var messagesVersion = 0

    init {
        refreshConversations()
        recovery.snapshot.chat.conversationId?.let { openConversation(it, recovery.snapshot.chat.title) }
    }

    private fun persistDraft(uncertain: Boolean = _uiState.value.requiresReload) {
        val state = _uiState.value
        recovery.update { it.copy(chat = ChatDraft(state.draft, state.conversationId, state.title,
            state.memoContext, uncertain, state.memoAction)) }
    }

    fun editDraft(text: String) {
        if (!_uiState.value.isSending) {
            _uiState.update { it.copy(draft = text, error = if (it.error == ApiIssue.InvalidInput) null else it.error,
                memoAction = it.memoAction?.takeIf { action -> action == MemoAction.Ask },
                title = if (it.conversationId == null && it.memoAction != null && it.memoAction != MemoAction.Ask) null else it.title) }
            persistDraft()
            recovery.update { it.copy(destination = DraftDestination.Chat) }
        }
    }

    fun refreshConversations() {
        val version = ++historyVersion
        historyJob?.cancel()
        _uiState.update { it.copy(isLoadingConversations = true, historyError = null) }
        historyJob = viewModelScope.launch {
            try {
                val conversations = repository.conversations()
                if (version == historyVersion) _uiState.update { it.copy(conversations = conversations) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ApiException) {
                if (version == historyVersion) _uiState.update { it.copy(historyError = error.issue) }
            } finally {
                if (version == historyVersion) _uiState.update { it.copy(isLoadingConversations = false) }
            }
        }
    }

    fun newConversation() {
        if (_uiState.value.isSending) return
        messagesVersion++
        messagesJob?.cancel()
        _uiState.update {
            it.copy(conversationId = null, title = null, messages = emptyList(),
                memoContext = emptyList(), memoAction = null, isLoadingMessages = false, sheetStatus = null, error = null, requiresReload = false)
        }
        persistDraft()
    }

    fun startWithMemos(memos: List<MemoContext>): Boolean {
        val state = _uiState.value
        if (state.isSending || state.isLoadingMessages || memos.isEmpty() || memos.size > MAX_CHAT_MEMOS ||
            memos.map { it.id }.distinct().size != memos.size ||
            memoContextSize(memos) > MAX_MEMO_CONTEXT_CHARACTERS) return false
        newConversation()
        _uiState.update { it.copy(memoContext = memos) }
        persistDraft()
        recovery.update { it.copy(destination = DraftDestination.Chat) }
        return true
    }

    fun startMemoAction(memo: MemoContext, action: MemoAction, replaceDraft: Boolean = false): Boolean {
        val state = _uiState.value
        if (state.isSending || state.isLoadingMessages || memoContextSize(listOf(memo)) > MAX_MEMO_CONTEXT_CHARACTERS ||
            (action != MemoAction.Ask && state.draft.isNotBlank() && !replaceDraft)) return false
        newConversation()
        _uiState.update { it.copy(memoContext = listOf(memo), memoAction = action,
            title = "${action.titlePrefix} · ${memo.displayTitle}",
            draft = if (action == MemoAction.Ask) it.draft else action.prompt) }
        persistDraft()
        recovery.update { it.copy(destination = DraftDestination.Chat) }
        if (action != MemoAction.Ask) send()
        return true
    }

    fun removeMemo(id: String) {
        if (_uiState.value.conversationId == null && !_uiState.value.isSending) {
            _uiState.update { it.copy(memoContext = it.memoContext.filterNot { memo -> memo.id == id },
                memoAction = null, title = if (it.memoAction != null) null else it.title) }
            persistDraft()
        }
    }

    fun selectConversation(conversation: Conversation) = openConversation(conversation.id, conversation.title)

    fun reloadConversation() {
        val state = _uiState.value
        val id = state.conversationId
        if (id == null) refreshConversations() else openConversation(id, state.title)
    }

    private fun openConversation(id: String, title: String?) {
        if (_uiState.value.isSending) return
        val version = ++messagesVersion
        messagesJob?.cancel()
        _uiState.update { it.copy(isLoadingMessages = true, error = null) }
        messagesJob = viewModelScope.launch {
            try {
                val content = repository.messages(id)
                currentCoroutineContext().ensureActive()
                if (version == messagesVersion) {
                    _uiState.update {
                        it.copy(conversationId = id, title = title, messages = content.messages,
                            memoContext = content.memoContext,
                            sheetStatus = null, error = null, requiresReload = false, memoAction = null)
                    }
                    persistDraft()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ApiException) {
                if (version == messagesVersion) _uiState.update { it.copy(error = error.issue) }
            } finally {
                if (version == messagesVersion) _uiState.update { it.copy(isLoadingMessages = false) }
            }
        }
    }

    fun send() {
        val state = _uiState.value
        if (state.isSending || state.isLoadingMessages || state.requiresReload) return
        val message = state.draft.trim()
        if (message.isEmpty() || chatCharacterCount(message) > MAX_CHAT_CHARACTERS) {
            _uiState.update { it.copy(error = ApiIssue.InvalidInput) }
            return
        }
        _uiState.update { it.copy(isSending = true, error = null, sheetStatus = null) }
        persistDraft(uncertain = true)
        viewModelScope.launch {
            try {
                if (!recovery.flush()) {
                    persistDraft(uncertain = false)
                    return@launch
                }
                val turn = repository.send(message, state.conversationId,
                    if (state.conversationId == null) state.memoContext.map { it.id } else emptyList(),
                    if (state.conversationId == null) state.memoAction else null)
                currentCoroutineContext().ensureActive()
                if (turn.messages.any { added -> _uiState.value.messages.any { it.id == added.id } }) {
                    throw ApiException(ApiIssue.InvalidResponse, outcomeUnknown = true)
                }
                _uiState.update {
                    it.copy(draft = "", conversationId = turn.conversationId,
                        title = state.memoAction?.let { action -> "${action.titlePrefix} · ${turn.memoContext.first().displayTitle}" }
                            ?: it.title ?: message.take(60), messages = it.messages + turn.messages, memoAction = null,
                        memoContext = turn.memoContext,
                        sheetStatus = turn.sheetStatus, requiresReload = false, error = null)
                }
                persistDraft()
                refreshConversations()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ApiException) {
                _uiState.update {
                    it.copy(error = error.issue,
                        requiresReload = error.outcomeUnknown || error.issue == ApiIssue.Conflict)
                }
                persistDraft()
            } finally {
                _uiState.update { it.copy(isSending = false) }
            }
        }
    }

    companion object {
        fun factory(repository: ChatRepository, recovery: RecoveryStore = RecoveryStore()): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatViewModel(repository, recovery) }
        }
    }
}
