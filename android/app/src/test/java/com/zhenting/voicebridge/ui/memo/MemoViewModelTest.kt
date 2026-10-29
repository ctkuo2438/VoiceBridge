package com.zhenting.voicebridge.ui.memo

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.zhenting.voicebridge.MainDispatcherRule
import com.zhenting.voicebridge.data.model.Memo
import com.zhenting.voicebridge.data.model.SheetSaveStatus
import com.zhenting.voicebridge.data.remote.ApiException
import com.zhenting.voicebridge.data.remote.ApiIssue
import com.zhenting.voicebridge.data.repository.MemoRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemoViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private val stores = mutableListOf<ViewModelStore>()
    @After fun tearDown() { stores.forEach { it.clear() } }
    private fun model(repo: FakeMemo): MemoViewModel {
        val store = ViewModelStore().also { stores.add(it) }
        return ViewModelProvider(store, MemoViewModel.factory(repo))[MemoViewModel::class.java]
    }

    @Test
    fun nothingIsSavedUntilConfirmedAndRepeatedConfirmationSendsOnlyOnce() = runTest {
        val repo = FakeMemo().apply { gate = CompletableDeferred() }
        val model = model(repo)
        model.editDraft("  My note  "); model.requestSave(); runCurrent()
        assertEquals("My note", model.uiState.value.confirmation)
        assertTrue(repo.prompts.isEmpty())
        model.cancelConfirmation()
        assertEquals("  My note  ", model.uiState.value.draft)
        model.requestSave(); model.saveConfirmed(); model.saveConfirmed(); runCurrent()
        assertEquals(listOf("My note"), repo.prompts)
        assertTrue(model.uiState.value.isSaving)
        repo.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals("", model.uiState.value.draft)
        assertEquals("My note", model.uiState.value.lastSaved!!.prompt)
        assertFalse(model.uiState.value.isSaving)
    }

    @Test
    fun aLostResponseKeepsTheDraftAndAnExplicitRetryUsesTheSameRequestId() = runTest {
        val repo = FakeMemo().apply { failure = ApiException(ApiIssue.Network, true) }
        val model = model(repo)
        model.editDraft("Keep this note"); model.requestSave(); model.saveConfirmed()
        advanceUntilIdle()
        assertEquals("Keep this note", model.uiState.value.draft)
        assertNull(model.uiState.value.lastSaved)
        assertTrue(model.uiState.value.outcomeUnknown)
        assertEquals(1, repo.prompts.size)
        repo.failure = null
        model.requestSave(); model.saveConfirmed(); advanceUntilIdle()
        assertEquals(2, repo.requestIds.size)
        assertEquals(repo.requestIds[0], repo.requestIds[1])
        assertEquals("", model.uiState.value.draft)
    }

    @Test
    fun aSavedMemoWithAnUnconfirmedSheetCopyStillClearsTheDraftAndAppearsInHistory() = runTest {
        val repo = FakeMemo().apply { sheetStatus = SheetSaveStatus.Unconfirmed }
        val model = model(repo)
        model.editDraft("Keep this note"); model.requestSave(); model.saveConfirmed(); advanceUntilIdle()
        assertEquals("", model.uiState.value.draft)
        assertNull(model.uiState.value.error)
        assertEquals(SheetSaveStatus.Unconfirmed, model.uiState.value.lastSaved!!.sheetStatus)
        assertEquals("Keep this note", model.uiState.value.memos.single().prompt)
    }

    @Test
    fun failedHistoryRefreshKeepsTheDraftAndPreviouslyLoadedMemos() = runTest {
        val repo = FakeMemo().apply { records = listOf(Memo("old", "Saved before", "2026-10-28T10:00:00Z", SheetSaveStatus.Saved)) }
        val model = model(repo)
        model.refreshHistory(); advanceUntilIdle()
        model.editDraft("Unsent draft")
        repo.historyFailure = ApiException(ApiIssue.Network)
        model.refreshHistory(); advanceUntilIdle()
        assertEquals("Unsent draft", model.uiState.value.draft)
        assertEquals("Saved before", model.uiState.value.memos.single().prompt)
        assertEquals(ApiIssue.Network, model.uiState.value.historyError)
        assertNull(model.uiState.value.error)
        assertFalse(model.uiState.value.isLoadingHistory)
    }

    @Test
    fun aHistoryResponseStartedBeforeSavingCannotRemoveTheNewMemo() = runTest {
        val repo = FakeMemo()
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        repo.historyRead = {
            if (++reads == 1) {
                withContext(NonCancellable) { gate.await() }
                emptyList()
            } else repo.records
        }
        val model = model(repo)
        model.refreshHistory(); runCurrent()
        model.editDraft("New memo"); model.requestSave(); model.saveConfirmed(); runCurrent()
        assertEquals("New memo", model.uiState.value.memos.single().prompt)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("New memo", model.uiState.value.memos.single().prompt)
        assertFalse(model.uiState.value.isLoadingHistory)
    }

    @Test
    fun oneOrMultipleMemosCanBeSelectedAndARefreshOnlyKeepsAvailableSelections() = runTest {
        val repo = FakeMemo().apply {
            records = listOf("a", "b", "c").map { Memo(it, "Note $it", "date", SheetSaveStatus.Saved) }
        }
        val model = model(repo)
        model.refreshHistory(); advanceUntilIdle()
        model.toggleMemo("a")
        assertEquals(setOf("a"), model.uiState.value.selectedMemoIds)
        model.toggleMemo("b")
        assertEquals(setOf("a", "b"), model.uiState.value.selectedMemoIds)
        model.toggleMemo("a")
        assertEquals(setOf("b"), model.uiState.value.selectedMemoIds)
        repo.records = repo.records.filterNot { it.id == "b" }
        model.refreshHistory(); advanceUntilIdle()
        assertTrue(model.uiState.value.selectedMemoIds.isEmpty())
    }

    private class FakeMemo : MemoRepository {
        val prompts = mutableListOf<String>()
        val requestIds = mutableListOf<String>()
        var records = emptyList<Memo>()
        var gate: CompletableDeferred<Unit>? = null
        var failure: ApiException? = null
        var historyFailure: ApiException? = null
        var historyRead: (suspend () -> List<Memo>)? = null
        var sheetStatus = SheetSaveStatus.Saved
        override suspend fun memos(): List<Memo> {
            historyFailure?.let { throw it }
            return historyRead?.invoke() ?: records
        }
        override suspend fun save(prompt: String, requestId: String): Memo {
            prompts.add(prompt)
            requestIds.add(requestId)
            gate?.await()
            failure?.let { throw it }
            return Memo(requestId, prompt, "2026-10-28T12:00:00+00:00", sheetStatus)
                .also { records = listOf(it) + records.filterNot { old -> old.id == it.id } }
        }
    }
}
