package com.zhenting.voicebridge.ui.memo

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.zhenting.voicebridge.MainDispatcherRule
import com.zhenting.voicebridge.data.model.Memo
import com.zhenting.voicebridge.data.model.SheetSaveStatus
import com.zhenting.voicebridge.data.model.defaultMemoTitle
import com.zhenting.voicebridge.data.remote.ApiException
import com.zhenting.voicebridge.data.remote.ApiIssue
import com.zhenting.voicebridge.data.repository.MemoRepository
import com.zhenting.voicebridge.data.local.RecoveryStore
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
    private fun model(repo: FakeMemo, recovery: RecoveryStore = RecoveryStore()): MemoViewModel {
        val store = ViewModelStore().also { stores.add(it) }
        return ViewModelProvider(store, MemoViewModel.factory(repo, recovery))[MemoViewModel::class.java]
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
    fun aDraftRestoresWithoutCreatingAMemoAndSuccessfulSaveClearsLocalRecovery() = runTest {
        val recovery = RecoveryStore()
        val repo = FakeMemo()
        val original = model(repo, recovery)
        original.editDraft("未完成的 note 🌱")
        stores.last().clear()
        val restored = model(repo, recovery)
        assertEquals("未完成的 note 🌱", restored.uiState.value.draft)
        assertTrue(repo.prompts.isEmpty())
        restored.requestSave(); restored.saveConfirmed(); advanceUntilIdle()
        stores.last().clear()
        assertEquals("", model(repo, recovery).uiState.value.draft)
        assertEquals(listOf("未完成的 note 🌱"), repo.prompts)
    }

    @Test
    fun restartingAfterALostSaveResponseReusesThePersistedRequestId() = runTest {
        val recovery = RecoveryStore()
        val repo = FakeMemo().apply { failure = ApiException(ApiIssue.Timeout, true) }
        val original = model(repo, recovery)
        original.editDraft("Keep exactly one memo"); original.requestSave(); original.saveConfirmed()
        advanceUntilIdle(); stores.last().clear()
        val restored = model(repo, recovery)
        assertTrue(restored.uiState.value.outcomeUnknown)
        repo.failure = null
        restored.requestSave(); restored.saveConfirmed(); advanceUntilIdle()
        assertEquals(2, repo.requestIds.size)
        assertEquals(repo.requestIds.first(), repo.requestIds.last())
        assertNull(recovery.snapshot.memo.requestId)
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

    @Test
    fun titlesRestoreWithTheDraftAndAreSavedOnlyAfterConfirmation() = runTest {
        val recovery = RecoveryStore()
        val repo = FakeMemo()
        model(repo, recovery).apply { editDraftTitle("課程回顧"); editDraft("Class notes") }
        stores.last().clear()
        val restored = model(repo, recovery)
        assertEquals("課程回顧", restored.uiState.value.draftTitle)
        assertTrue(repo.prompts.isEmpty())
        restored.requestSave(); restored.saveConfirmed(); advanceUntilIdle()
        assertEquals("課程回顧", repo.records.single().title)
        assertEquals("", recovery.snapshot.memo.title)
    }

    @Test
    fun editingUpdatesTheExistingMemoAndKeepsTheUnfinishedNewMemoSeparate() = runTest {
        val recovery = RecoveryStore()
        val repo = FakeMemo().apply { records = listOf(Memo("saved", "Earlier text", "date", SheetSaveStatus.Saved, "Earlier title")) }
        val model = model(repo, recovery)
        model.refreshHistory(); advanceUntilIdle()
        model.editDraftTitle("Unfinished title"); model.editDraft("Unfinished new memo")
        model.startEditing("saved"); model.editSavedTitle("Revised title"); model.editSavedText("Revised text")
        model.saveEditedMemo(); model.saveEditedMemo(); advanceUntilIdle()
        val saved = repo.records.single()
        assertEquals("saved", saved.id)
        assertEquals("Revised title", saved.title)
        assertEquals("Revised text", saved.prompt)
        assertEquals(1, saved.version)
        assertEquals(listOf("saved" to 0), repo.updates)
        assertTrue(repo.prompts.isEmpty())
        assertEquals("Unfinished new memo", model.uiState.value.draft)
        assertEquals("Unfinished title", model.uiState.value.draftTitle)
        assertNull(recovery.snapshot.memoEdit)
        assertTrue(model.uiState.value.lastSavedWasEdited)
    }

    @Test
    fun unfinishedSavedMemoEditsRestoreAndCancelDoesNotModifyTheSavedNote() = runTest {
        val recovery = RecoveryStore()
        val original = Memo("saved", "Original", "date", SheetSaveStatus.Saved, "Original title")
        val repo = FakeMemo().apply { records = listOf(original) }
        val first = model(repo, recovery)
        first.refreshHistory(); advanceUntilIdle()
        first.startEditing("saved"); first.editSavedText("Unfinished edit")
        stores.last().clear()
        val restored = model(repo, recovery)
        assertEquals("Unfinished edit", restored.uiState.value.editing!!.draft.text)
        assertEquals(listOf(original), repo.records)
        restored.cancelEditing()
        assertNull(recovery.snapshot.memoEdit)
        assertEquals(listOf(original), repo.records)
    }

    @Test
    fun conflictPreservesEditsUntilTheUserExplicitlyReloadsTheNewVersion() = runTest {
        val original = Memo("saved", "Original", "date", SheetSaveStatus.Saved, "Original title")
        val repo = FakeMemo().apply { records = listOf(original); updateFailure = ApiException(ApiIssue.Conflict) }
        val model = model(repo)
        model.refreshHistory(); advanceUntilIdle()
        model.startEditing("saved"); model.editSavedText("My changes"); model.saveEditedMemo(); advanceUntilIdle()
        assertEquals(ApiIssue.Conflict, model.uiState.value.editing!!.error)
        assertEquals("My changes", model.uiState.value.editing!!.draft.text)
        model.editSavedText("Accidental replacement")
        assertEquals("My changes", model.uiState.value.editing!!.draft.text)
        repo.records = listOf(original.copy(prompt = "Changed on another device", version = 1))
        repo.updateFailure = null
        model.reloadEditedMemo(); advanceUntilIdle()
        assertEquals("Changed on another device", model.uiState.value.editing!!.draft.text)
        model.editSavedText("Changes after reload"); model.saveEditedMemo(); advanceUntilIdle()
        assertEquals(listOf("saved" to 0, "saved" to 1), repo.updates)
        assertEquals("Changes after reload", repo.records.single().prompt)
    }

    @Test
    fun anUncertainUpdateRestoresItsExactChangesAndCanBeRetriedAfterRestart() = runTest {
        val recovery = RecoveryStore()
        val repo = FakeMemo().apply {
            records = listOf(Memo("saved", "Original", "date", SheetSaveStatus.Saved))
            updateFailure = ApiException(ApiIssue.Timeout, outcomeUnknown = true)
        }
        val first = model(repo, recovery)
        first.refreshHistory(); advanceUntilIdle()
        first.startEditing("saved"); first.editSavedText("My changes"); first.saveEditedMemo(); advanceUntilIdle()
        stores.last().clear()
        val restored = model(repo, recovery)
        assertTrue(restored.uiState.value.editing!!.draft.outcomeUnknown)
        restored.editSavedText("Different changes")
        assertEquals("My changes", restored.uiState.value.editing!!.draft.text)
        repo.updateFailure = null
        restored.saveEditedMemo(); advanceUntilIdle()
        assertEquals(listOf("saved" to 0, "saved" to 0), repo.updates)
        assertNull(recovery.snapshot.memoEdit)
    }

    @Test
    fun searchMatchesTitlesAndBodiesWithoutLosingDraftsOrHiddenSelections() = runTest {
        val repo = FakeMemo().apply { records = listOf(
            Memo("a", "日常紀錄", "date", SheetSaveStatus.Saved, "Project plan"),
            Memo("b", "報告需要修改", "date", SheetSaveStatus.Saved, "Daily note")) }
        val model = model(repo)
        model.refreshHistory(); advanceUntilIdle()
        model.editDraft("Unfinished"); model.toggleMemo("a")
        model.search("PROJECT")
        assertEquals(listOf("a"), model.uiState.value.visibleMemos.map { it.id })
        model.search("報告")
        assertEquals(listOf("b"), model.uiState.value.visibleMemos.map { it.id })
        assertEquals(setOf("a"), model.uiState.value.selectedMemoIds)
        model.search("no match")
        assertTrue(model.uiState.value.visibleMemos.isEmpty())
        model.search("")
        assertEquals(2, model.uiState.value.visibleMemos.size)
        assertEquals("Unfinished", model.uiState.value.draft)
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
        var updateFailure: ApiException? = null
        val updates = mutableListOf<Pair<String, Int>>()
        override suspend fun memos(): List<Memo> {
            historyFailure?.let { throw it }
            return historyRead?.invoke() ?: records
        }
        override suspend fun save(prompt: String, requestId: String, title: String?): Memo {
            prompts.add(prompt)
            requestIds.add(requestId)
            gate?.await()
            failure?.let { throw it }
            return Memo(requestId, prompt, "2026-10-28T12:00:00+00:00", sheetStatus, title ?: defaultMemoTitle(prompt))
                .also { records = listOf(it) + records.filterNot { old -> old.id == it.id } }
        }
        override suspend fun update(memoId: String, title: String, prompt: String, version: Int): Memo {
            updates.add(memoId to version)
            updateFailure?.let { throw it }
            val previous = records.find { it.id == memoId } ?: throw ApiException(ApiIssue.NotFound)
            if (previous.version != version) throw ApiException(ApiIssue.Conflict)
            return previous.copy(title = title, prompt = prompt, version = version + 1,
                updatedAt = "2026-10-31T12:00:00Z", sheetStatus = SheetSaveStatus.Outdated)
                .also { updated -> records = listOf(updated) + records.filterNot { it.id == memoId } }
        }
    }
}
