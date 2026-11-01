package com.zhenting.voicebridge.ui.chat

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.zhenting.voicebridge.MainDispatcherRule
import com.zhenting.voicebridge.data.model.*
import com.zhenting.voicebridge.data.remote.ApiException
import com.zhenting.voicebridge.data.remote.ApiIssue
import com.zhenting.voicebridge.data.repository.ChatRepository
import com.zhenting.voicebridge.data.local.RecoveryStore
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
    private fun model(repo: FakeChat, recovery: RecoveryStore = RecoveryStore()): ChatViewModel {
        val store = ViewModelStore().also { stores.add(it) }
        return ViewModelProvider(store, ChatViewModel.factory(repo, recovery))[ChatViewModel::class.java]
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
    fun composerRestoresItsQuestionAndSelectedMemosWithoutSending() = runTest {
        val recovery = RecoveryStore()
        val repo = FakeChat()
        val original = model(repo, recovery)
        val context = listOf(MemoContext("m-1", "Reference note", "date"))
        original.editDraft("Unsent question"); original.startWithMemos(context)
        stores.last().clear()
        val restored = model(repo, recovery)
        assertEquals("Unsent question", restored.uiState.value.draft)
        assertEquals(context, restored.uiState.value.memoContext)
        assertTrue(repo.sentTo.isEmpty())
        restored.send(); advanceUntilIdle()
        stores.last().clear()
        val afterSend = model(repo, recovery)
        advanceUntilIdle()
        assertEquals("", afterSend.uiState.value.draft)
        assertEquals("c-1", afterSend.uiState.value.conversationId)
    }

    @Test
    fun interruptedNewConversationCannotSilentlyResendOnRestoration() = runTest {
        val recovery = RecoveryStore()
        val repo = FakeChat().apply { gate = CompletableDeferred() }
        val original = model(repo, recovery)
        original.editDraft("Possibly sent"); original.send(); runCurrent()
        stores.last().clear()
        val restored = model(repo, recovery)
        assertEquals("Possibly sent", restored.uiState.value.draft)
        assertTrue(restored.uiState.value.requiresReload)
        assertEquals(ApiIssue.SaveUnconfirmed, restored.uiState.value.error)
        restored.send(); runCurrent()
        assertEquals(1, repo.sentTo.size)
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

    @Test
    fun quickActionsSendThePreparedQuestionOnceAndAskLeavesTheQuestionForTheUser() = runTest {
        val memo = MemoContext("m-1", "Report due Friday", "date", "Project")
        val repo = FakeChat().apply { gate = CompletableDeferred() }
        val model = model(repo)
        assertTrue(model.startMemoAction(memo, MemoAction.Summary))
        assertFalse(model.startMemoAction(memo, MemoAction.Todos))
        runCurrent()
        assertEquals(listOf(MemoAction.Summary), repo.sentActions)
        assertEquals(listOf(MemoAction.Summary.prompt), repo.prompts)
        assertEquals(listOf(listOf("m-1")), repo.sentMemoIds)
        repo.gate!!.complete(Unit); advanceUntilIdle()
        model.editDraft("What is the deadline?")
        assertTrue(model.startMemoAction(memo, MemoAction.Ask))
        assertEquals("What is the deadline?", model.uiState.value.draft)
        assertEquals(1, repo.sentTo.size)
        model.send(); advanceUntilIdle()
        assertEquals(listOf(MemoAction.Summary, MemoAction.Ask), repo.sentActions)
    }

    @Test
    fun automaticActionsRequireExplicitReplacementOfAnUnsentDraft() = runTest {
        val repo = FakeChat()
        val model = model(repo)
        model.editDraft("Unsent question")
        val memo = MemoContext("m-1", "Note", "date")
        assertFalse(model.startMemoAction(memo, MemoAction.Bilingual))
        assertEquals("Unsent question", model.uiState.value.draft)
        assertTrue(repo.sentTo.isEmpty())
        assertTrue(model.startMemoAction(memo, MemoAction.Bilingual, replaceDraft = true))
        advanceUntilIdle()
        assertEquals(listOf(MemoAction.Bilingual.prompt), repo.prompts)
    }

    @Test
    fun anInterruptedQuickActionRestoresWithoutAutomaticallySendingAgain() = runTest {
        val recovery = RecoveryStore()
        val repo = FakeChat().apply { gate = CompletableDeferred() }
        val original = model(repo, recovery)
        original.startMemoAction(MemoContext("m-1", "Note", "date"), MemoAction.Todos)
        runCurrent(); stores.last().clear()
        val restored = model(repo, recovery)
        runCurrent()
        assertEquals(MemoAction.Todos, restored.uiState.value.memoAction)
        assertEquals(MemoAction.Todos.prompt, restored.uiState.value.draft)
        assertTrue(restored.uiState.value.requiresReload)
        assertEquals(1, repo.sentTo.size)
    }

    @Test
    fun editingAPreparedActionTurnsItIntoAnOrdinaryQuestionAboutTheNote() = runTest {
        val recovery = RecoveryStore()
        val repo = FakeChat().apply { failure = ApiException(ApiIssue.Unavailable) }
        val model = model(repo, recovery)
        model.startMemoAction(MemoContext("m-1", "Note", "date"), MemoAction.Summary)
        advanceUntilIdle()
        model.editDraft("A different question")
        assertNull(model.uiState.value.memoAction)
        assertNull(recovery.snapshot.chat.memoAction)
        assertEquals(listOf("m-1"), model.uiState.value.memoContext.map { it.id })
    }

    private class FakeChat : ChatRepository {
        val sentTo = mutableListOf<String?>()
        val sentMemoIds = mutableListOf<List<String>>()
        val sentActions = mutableListOf<MemoAction?>()
        val prompts = mutableListOf<String>()
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
        override suspend fun send(message: String, conversationId: String?, memoIds: List<String>, memoAction: MemoAction?): ChatTurn {
            sentActions.add(memoAction); prompts.add(message)
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
