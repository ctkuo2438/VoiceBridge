package com.zhenting.voicebridge.ui.chat

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.zhenting.voicebridge.MainDispatcherRule
import com.zhenting.voicebridge.data.model.*
import com.zhenting.voicebridge.data.remote.ApiException
import com.zhenting.voicebridge.data.remote.ApiIssue
import com.zhenting.voicebridge.data.repository.ChatRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private val stores = mutableListOf<ViewModelStore>()
    @After fun tearDown() { stores.forEach { it.clear() } }
    private fun model(repo: FakeChat): ChatViewModel {
        val store = ViewModelStore().also { stores.add(it) }
        return ViewModelProvider(store, ChatViewModel.factory(repo))[ChatViewModel::class.java]
    }

    @Test
    fun repeatedTapsSendOnceAndTheNextTurnUsesTheReturnedConversationId() = runTest {
        val repo = FakeChat()
        repo.gate = CompletableDeferred()
        val model = model(repo)
        model.editDraft("Hi")
        model.send(); model.send()
        runCurrent()
        assertEquals(listOf<String?>(null), repo.sentTo)
        repo.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("c-1", model.uiState.value.conversationId)
        assertEquals(2, model.uiState.value.messages.size)
        assertEquals("", model.uiState.value.draft)
        assertEquals(SheetSaveStatus.Unconfirmed, model.uiState.value.sheetStatus)
        assertNull(model.uiState.value.error)
        model.editDraft("Again"); model.send(); advanceUntilIdle()
        assertEquals(listOf(null, "c-1"), repo.sentTo)
        assertEquals(4, model.uiState.value.messages.size)
    }

    @Test
    fun uncertainSendKeepsTheDraftAndRequiresAnExplicitReloadOrNewChat() = runTest {
        val repo = FakeChat().apply { failure = ApiException(ApiIssue.Timeout, true) }
        val model = model(repo)
        model.editDraft("Keep me"); model.send(); advanceUntilIdle()
        assertEquals("Keep me", model.uiState.value.draft)
        assertTrue(model.uiState.value.requiresReload)
        model.send(); advanceUntilIdle()
        assertEquals(1, repo.sentTo.size)
        model.newConversation()
        assertFalse(model.uiState.value.requiresReload)
        assertEquals("Keep me", model.uiState.value.draft)
    }

    @Test
    fun selectingHistoryLoadsItsMessagesAndNewChatResetsOnlyTheConversation() = runTest {
        val repo = FakeChat()
        val model = model(repo)
        model.selectConversation(Conversation("old", "Earlier chat", "date", "date"))
        advanceUntilIdle()
        assertEquals("old", model.uiState.value.conversationId)
        assertEquals("Earlier reply", model.uiState.value.messages.single().content)
        model.editDraft("Unsent question"); model.newConversation()
        assertNull(model.uiState.value.conversationId)
        assertTrue(model.uiState.value.messages.isEmpty())
        assertEquals("Unsent question", model.uiState.value.draft)
    }

    @Test
    fun aHistoryRefreshFailureDoesNotTurnASuccessfulReplyIntoAChatError() = runTest {
        val repo = FakeChat().apply { historyFailure = true }
        val model = model(repo)
        model.editDraft("Hi"); model.send(); advanceUntilIdle()
        assertEquals(2, model.uiState.value.messages.size)
        assertNull(model.uiState.value.error)
        assertEquals(ApiIssue.Unavailable, model.uiState.value.historyError)
    }

    @Test
    fun importingMemosKeepsTheQuestionAndSendsIdsOnlyOnTheFirstTurn() = runTest {
        val repo = FakeChat()
        val model = model(repo)
        val memos = listOf(MemoContext("m-1", "Golf note", "date"), MemoContext("m-2", "Friday trip", "date"))
        model.editDraft("Summarize my notes")
        assertTrue(model.startWithMemos(memos))
        assertEquals("Summarize my notes", model.uiState.value.draft)
        assertTrue(repo.sentTo.isEmpty())
        model.send(); advanceUntilIdle()
        model.editDraft("Tell me more"); model.send(); advanceUntilIdle()
        assertEquals(listOf(listOf("m-1", "m-2"), emptyList<String>()), repo.sentMemoIds)
        assertEquals(memos.map { it.id }, model.uiState.value.memoContext.map { it.id })
        model.newConversation()
        assertTrue(model.uiState.value.memoContext.isEmpty())
    }

    @Test
    fun historyRestoresMemoSnapshotsAndNewDraftsAllowRemovingAnAttachment() = runTest {
        val repo = FakeChat().apply { context = listOf(MemoContext("m-1", "Saved context", "date")) }
        val model = model(repo)
        model.selectConversation(Conversation("old", "Earlier chat", "date", "date")); advanceUntilIdle()
        assertEquals("Saved context", model.uiState.value.memoContext.single().prompt)
        model.removeMemo("m-1")
        assertEquals(1, model.uiState.value.memoContext.size)
        assertTrue(model.startWithMemos(repo.context))
        model.removeMemo("m-1")
        assertTrue(model.uiState.value.memoContext.isEmpty())
    }

    private class FakeChat : ChatRepository {
        val sentTo = mutableListOf<String?>()
        val sentMemoIds = mutableListOf<List<String>>()
        var context = emptyList<MemoContext>()
        var gate: CompletableDeferred<Unit>? = null
        var failure: ApiException? = null
        var historyFailure = false
        override suspend fun conversations(): List<Conversation> {
            if (historyFailure) throw ApiException(ApiIssue.Unavailable)
            return emptyList()
        }
        override suspend fun messages(conversationId: String) = ConversationContent(listOf(
            ChatMessage(100, MessageRole.Assistant, "Earlier reply", "date")), context)
        override suspend fun send(message: String, conversationId: String?, memoIds: List<String>): ChatTurn {
            sentTo.add(conversationId)
            sentMemoIds.add(memoIds)
            if (memoIds.isNotEmpty()) context = memoIds.map { MemoContext(it, "Attached $it", "date") }
            gate?.await()
            failure?.let { throw it }
            val id = sentTo.size.toLong() * 2
            return ChatTurn(conversationId ?: "c-1", listOf(
                ChatMessage(id - 1, MessageRole.User, message, "date"),
                ChatMessage(id, MessageRole.Assistant, "Reply", "date")), SheetSaveStatus.Unconfirmed, context)
        }
    }
}
