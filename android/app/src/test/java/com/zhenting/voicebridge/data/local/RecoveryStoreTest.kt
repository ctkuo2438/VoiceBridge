package com.zhenting.voicebridge.data.local

import androidx.datastore.core.CorruptionException
import com.zhenting.voicebridge.data.model.Memo
import com.zhenting.voicebridge.data.model.MemoAction
import com.zhenting.voicebridge.data.model.asContext
import com.zhenting.voicebridge.data.model.SheetSaveStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private suspend fun TestScope.open(directory: File): Pair<PersistentRecoveryStore, Job> {
        val job = SupervisorJob(backgroundScope.coroutineContext[Job])
        return PersistentRecoveryStore.open(directory, CoroutineScope(StandardTestDispatcher(testScheduler) + job)) to job
    }

    @Test
    fun diskReopeningRestoresTheLatestUnicodeDraftAndMemoRetryKey() = runTest {
        val directory = temporary.newFolder()
        val (store, job) = open(directory)
        store.update { it.copy(memo = MemoDraft("原始文字", "確認文字", "same-request-id")) }
        repeat(30) { count -> store.update { it.copy(memo = it.memo.copy(text = "草稿 $count 🌱")) } }
        assertTrue(store.flush())
        job.cancelAndJoin()
        val (restored, restoredJob) = open(directory)
        try {
            assertEquals(MemoDraft("草稿 29 🌱", "確認文字", "same-request-id"), restored.snapshot.memo)
            assertEquals(LocalSaveStatus.Saved, restored.saveProgress.value.status)
        } finally { restoredJob.cancelAndJoin() }
    }

    @Test
    fun speechAcceptanceAndDraftAppendArePersistedAsOneChange() = runTest {
        val directory = temporary.newFolder()
        val audio = File(directory, "audio").apply { mkdirs() }
        val clip = File(audio, "clip.m4a").apply { writeText("audio") }
        val (store, job) = open(directory)
        // The store initially prunes unregistered files; recording normally starts after it opens.
        clip.writeText("audio")
        store.update { it.copy(memo = MemoDraft("Earlier note"),
            speech = PendingSpeech("speech-1", DraftDestination.Memo, clip.absolutePath, "New text")) }
        assertTrue(store.flush())
        assertEquals(DraftDestination.Memo to "Earlier note\nNew text", store.consumeSpeech("speech-1"))
        assertNull(store.consumeSpeech("speech-1"))
        assertTrue(store.flush())
        job.cancelAndJoin()
        val (restored, restoredJob) = open(directory)
        try {
            assertEquals("Earlier note\nNew text", restored.snapshot.memo.text)
            assertNull(restored.snapshot.speech)
        } finally { restoredJob.cancelAndJoin() }
    }

    @Test
    fun registeredAudioSurvivesReopeningWhileOrphansAndOtherAccountsStaySeparate() = runTest {
        val firstDirectory = temporary.newFolder("alice")
        val (first, firstJob) = open(firstDirectory)
        val audio = File(firstDirectory, "audio").apply { mkdirs() }
        val clip = File(audio, "pending.m4a").apply { writeText("audio") }
        first.update { it.copy(memo = MemoDraft("Alice private draft"),
            speech = PendingSpeech("pending", DraftDestination.Memo, clip.absolutePath)) }
        assertTrue(first.flush()); firstJob.cancelAndJoin()
        val orphan = File(audio, "interrupted.m4a").apply { writeText("unfinished") }
        val (restored, restoredJob) = open(firstDirectory)
        val (other, otherJob) = open(temporary.newFolder("bob"))
        try {
            assertTrue(clip.exists())
            assertFalse(orphan.exists())
            assertEquals("Alice private draft", restored.snapshot.memo.text)
            assertEquals(RecoverySnapshot(), other.snapshot)
        } finally { restoredJob.cancelAndJoin(); otherJob.cancelAndJoin() }
    }

    @Test
    fun corruptDraftFileIsNotSilentlyErasedAndCanBeRetriedAfterRepair() = runTest {
        val directory = temporary.newFolder()
        val file = File(directory, "drafts.json").apply { writeText("{invalid draft") }
        val original = file.readText()
        try {
            open(directory)
            fail("Corrupt data must be reported.")
        } catch (_: CorruptionException) {
            assertEquals(original, file.readText())
        }
        file.writeText("{}")
        val (repaired, job) = open(directory)
        try { assertEquals(RecoverySnapshot(), repaired.snapshot) } finally { job.cancelAndJoin() }
    }

    @Test
    fun oldDraftFilesStillLoadWhenTitleAndSavedMemoEditingFieldsAreAbsent() = runTest {
        val directory = temporary.newFolder()
        File(directory, "drafts.json").writeText("""{"memo":{"text":"Unfinished old note","pendingPrompt":"Old note","requestId":"keep-id"}}""")
        val (store, job) = open(directory)
        try {
            assertEquals("Unfinished old note", store.snapshot.memo.text)
            assertEquals("", store.snapshot.memo.title)
            assertEquals("keep-id", store.snapshot.memo.requestId)
            assertNull(store.snapshot.memoEdit)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun newMemoAndSavedMemoEditsPersistSeparatelyAcrossDiskReopening() = runTest {
        val directory = temporary.newFolder()
        val (store, job) = open(directory)
        val saved = Memo("saved", "Existing content", "date", SheetSaveStatus.Saved, "Existing title", version = 3)
        store.update { it.copy(memo = MemoDraft("New note", title = "New title"),
            memoEdit = MemoEditDraft(saved, "Changed title", "Changed content", outcomeUnknown = true)) }
        assertTrue(store.flush()); job.cancelAndJoin()
        val (restored, restoredJob) = open(directory)
        try {
            assertEquals("New note", restored.snapshot.memo.text)
            assertEquals("New title", restored.snapshot.memo.title)
            assertEquals("Changed content", restored.snapshot.memoEdit!!.text)
            assertEquals(3, restored.snapshot.memoEdit!!.memo.version)
            assertTrue(restored.snapshot.memoEdit!!.outcomeUnknown)
        } finally { restoredJob.cancelAndJoin() }
    }

    @Test
    fun olderAttachedNotesWithoutTitlesRemainReadable() = runTest {
        val directory = temporary.newFolder()
        File(directory, "drafts.json").writeText("""{"chat":{"text":"My question","memoContext":[{"id":"m-1","prompt":"Old note title\nDetails","createdAt":"date"}]}}""")
        val (store, job) = open(directory)
        try {
            assertEquals("Old note title", store.snapshot.chat.memoContext.single().displayTitle)
            assertNull(store.snapshot.chat.memoAction)
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun preparedNoteActionsAndSnapshotTitlesSurviveDiskReopening() = runTest {
        val directory = temporary.newFolder()
        val (store, job) = open(directory)
        val memo = Memo("m-1", "Report due Friday", "date", SheetSaveStatus.Saved, "Meeting")
        val draft = ChatDraft(MemoAction.Todos.prompt, memoContext = listOf(memo.asContext()),
            requiresReload = true, memoAction = MemoAction.Todos)
        store.update { it.copy(chat = draft) }
        assertTrue(store.flush()); job.cancelAndJoin()
        val (restored, restoredJob) = open(directory)
        try { assertEquals(draft, restored.snapshot.chat) } finally { restoredJob.cancelAndJoin() }
    }
}
