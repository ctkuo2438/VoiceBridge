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
import com.zhenting.voicebridge.data.model.MAX_CHAT_MEMOS
import com.zhenting.voicebridge.data.model.MAX_MEMO_CONTEXT_CHARACTERS
import com.zhenting.voicebridge.data.model.memoContextSize
import com.zhenting.voicebridge.data.remote.ApiException
import com.zhenting.voicebridge.data.remote.ApiIssue
import com.zhenting.voicebridge.data.repository.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(private val repository: ChatRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState = _uiState.asStateFlow()
    private var historyJob: Job? = null
    private var messagesJob: Job? = null
    private var historyVersion = 0
    private var messagesVersion = 0

    init { refreshConversations() }

    fun editDraft(text: String) {
        if (!_uiState.value.isSending) _uiState.update {
            it.copy(draft = text, error = if (it.error == ApiIssue.InvalidInput) null else it.error)
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
                memoContext = emptyList(), isLoadingMessages = false, sheetStatus = null, error = null, requiresReload = false)
        }
    }

    fun startWithMemos(memos: List<MemoContext>): Boolean {
        val state = _uiState.value
        if (state.isSending || state.isLoadingMessages || memos.isEmpty() || memos.size > MAX_CHAT_MEMOS ||
            memos.map { it.id }.distinct().size != memos.size ||
            memoContextSize(memos) > MAX_MEMO_CONTEXT_CHARACTERS) return false
        newConversation()
        _uiState.update { it.copy(memoContext = memos) }
        return true
    }

    fun removeMemo(id: String) {
        if (_uiState.value.conversationId == null && !_uiState.value.isSending) {
            _uiState.update { it.copy(memoContext = it.memoContext.filterNot { memo -> memo.id == id }) }
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
                if (version == messagesVersion) _uiState.update {
                    it.copy(conversationId = id, title = title, messages = content.messages,
                        memoContext = content.memoContext,
                        sheetStatus = null, error = null, requiresReload = false)
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
        viewModelScope.launch {
            try {
                val turn = repository.send(message, state.conversationId,
                    if (state.conversationId == null) state.memoContext.map { it.id } else emptyList())
                if (turn.messages.any { added -> _uiState.value.messages.any { it.id == added.id } }) {
                    throw ApiException(ApiIssue.InvalidResponse, outcomeUnknown = true)
                }
                _uiState.update {
                    it.copy(draft = "", conversationId = turn.conversationId,
                        title = it.title ?: message.take(60), messages = it.messages + turn.messages,
                        memoContext = turn.memoContext,
                        sheetStatus = turn.sheetStatus, requiresReload = false, error = null)
                }
                refreshConversations()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ApiException) {
                _uiState.update {
                    it.copy(error = error.issue,
                        requiresReload = error.outcomeUnknown || error.issue == ApiIssue.Conflict)
                }
            } finally {
                _uiState.update { it.copy(isSending = false) }
            }
        }
    }

    companion object {
        fun factory(repository: ChatRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatViewModel(repository) }
        }
    }
}
