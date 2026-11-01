package com.zhenting.voicebridge.ui.speech

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhenting.voicebridge.MainDispatcherRule
import com.zhenting.voicebridge.data.audio.AudioRecorder
import com.zhenting.voicebridge.data.model.MAX_RECORDING_SECONDS
import com.zhenting.voicebridge.data.model.SpeechException
import com.zhenting.voicebridge.data.model.SpeechIssue
import com.zhenting.voicebridge.data.repository.SpeechRepository
import com.zhenting.voicebridge.data.local.RecoveryStore
import com.zhenting.voicebridge.data.local.MemoDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    @get:Rule val temporary = TemporaryFolder()
    private val stores = mutableListOf<ViewModelStore>()
    @After fun tearDown() { stores.forEach { it.clear() } }

    private fun model(recorder: FakeRecorder, repo: SpeechRepository, recovery: RecoveryStore = RecoveryStore(), clock: () -> Long = { 0L }): SpeechViewModel {
        val store = ViewModelStore().also { stores.add(it) }
        return ViewModelProvider(store, viewModelFactory {
            initializer { SpeechViewModel(recorder, repo, clock, recovery) }
        })[SpeechViewModel::class.java]
    }

    @Test
    fun transcriptIsEditableAndRequiresAcceptanceBeforeItIsAddedToADraft() = runTest {
        val recorder = FakeRecorder()
        var calls = 0
        val repo = object : SpeechRepository {
            override suspend fun transcribe(audio: File): String { calls++; return "Recognized text" }
        }
        val model = model(recorder, repo)
        model.start(SpeechTarget.Chat)
        assertTrue(recorder.recording)
        model.stopAndTranscribe(); model.stopAndTranscribe(); runCurrent()
        assertEquals(1, calls)
        assertEquals(SpeechPhase.Review, model.uiState.value.phase)
        assertFalse(recorder.files.single().exists())
        model.editTranscript("Corrected text")
        var accepted: Pair<SpeechTarget, String>? = null
        model.acceptTranscript { target, text -> accepted = target to text }
        assertEquals(SpeechTarget.Chat to "Corrected text", accepted)
        assertEquals(SpeechPhase.Idle, model.uiState.value.phase)
    }

    @Test
    fun failedTranscriptionKeepsTheSameClipAndRetryDoesNotRecordAgain() = runTest {
        val recorder = FakeRecorder()
        var calls = 0
        val repo = object : SpeechRepository {
            override suspend fun transcribe(audio: File): String {
                calls++
                if (calls == 1) throw SpeechException(SpeechIssue.Network)
                return "Recovered speech"
            }
        }
        val model = model(recorder, repo)
        model.start(SpeechTarget.Memo); model.stopAndTranscribe(); runCurrent()
        assertEquals(SpeechPhase.Retry, model.uiState.value.phase)
        assertFalse(model.uiState.value.isBusy)
        assertTrue(recorder.files.single().exists())
        model.start(SpeechTarget.Chat)
        model.retry(); model.retry(); runCurrent()
        assertEquals(1, recorder.stops)
        assertEquals(1, recorder.files.size)
        assertEquals(2, calls)
        assertEquals(SpeechPhase.Review, model.uiState.value.phase)
        assertFalse(recorder.files.single().exists())
    }

    @Test
    fun failedClipSurvivesWorkspaceDestructionAndCanBeDiscardedAfterRestoring() = runTest {
        val recovery = RecoveryStore()
        val recorder = FakeRecorder()
        val repo = object : SpeechRepository {
            override suspend fun transcribe(audio: File): String = throw SpeechException(SpeechIssue.Timeout)
        }
        val original = model(recorder, repo, recovery)
        original.start(SpeechTarget.Memo); original.stopAndTranscribe(); runCurrent()
        stores.last().clear()
        assertTrue(recorder.files.single().exists())
        val restored = model(FakeRecorder(), repo, recovery)
        assertEquals(SpeechPhase.Retry, restored.uiState.value.phase)
        assertEquals(SpeechTarget.Memo, restored.uiState.value.target)
        restored.cancel()
        assertFalse(recorder.files.single().exists())
        assertNull(recovery.snapshot.speech)
    }

    @Test
    fun editedReviewRestoresAndAcceptingItAppendsToTheDraftExactlyOnce() = runTest {
        val recovery = RecoveryStore().apply { update { it.copy(memo = MemoDraft("Existing note")) } }
        val repo = object : SpeechRepository {
            override suspend fun transcribe(audio: File) = "Original transcript"
        }
        val original = model(FakeRecorder(), repo, recovery)
        original.start(SpeechTarget.Memo); original.stopAndTranscribe(); runCurrent()
        original.editTranscript("Corrected transcript")
        stores.last().clear()
        val restored = model(FakeRecorder(), repo, recovery)
        assertEquals("Corrected transcript", restored.uiState.value.text)
        var acceptances = 0
        val accept: (SpeechTarget, String) -> Unit = { target, text ->
            acceptances++
            assertEquals(SpeechTarget.Memo, target)
            assertEquals("Existing note\nCorrected transcript", text)
        }
        restored.acceptTranscript(accept); restored.acceptTranscript(accept)
        assertEquals(1, acceptances)
        assertEquals("Existing note\nCorrected transcript", recovery.snapshot.memo.text)
        assertNull(recovery.snapshot.speech)
    }

    @Test
    fun audioIsNotUploadedOrDeletedWhenLocalCheckpointFails() = runTest {
        val recovery = object : RecoveryStore() {
            var writable = false
            override suspend fun flush() = writable
        }
        var calls = 0
        val repo = object : SpeechRepository {
            override suspend fun transcribe(audio: File): String { calls++; return "Safe transcript" }
        }
        val recorder = FakeRecorder()
        val model = model(recorder, repo, recovery)
        model.start(SpeechTarget.Memo); model.stopAndTranscribe(); runCurrent()
        assertEquals(0, calls)
        assertTrue(recorder.files.single().exists())
        assertEquals(SpeechIssue.LocalStorage, model.uiState.value.error)
        recovery.writable = true
        model.retry(); runCurrent()
        assertEquals(1, calls)
        assertEquals(SpeechPhase.Review, model.uiState.value.phase)
        assertFalse(recorder.files.single().exists())
    }

    @Test
    fun recognizedAudioIsKeptUntilItsTextIsDurableAndRetryDoesNotCallTheApiAgain() = runTest {
        val recovery = object : RecoveryStore() {
            var writable = true
            override suspend fun flush() = writable
        }
        var calls = 0
        val repo = object : SpeechRepository {
            override suspend fun transcribe(audio: File): String {
                calls++
                recovery.writable = false
                return "Already recognized"
            }
        }
        val recorder = FakeRecorder()
        val model = model(recorder, repo, recovery)
        model.start(SpeechTarget.Chat); model.stopAndTranscribe(); runCurrent()
        assertTrue(recorder.files.single().exists())
        assertEquals(SpeechPhase.Retry, model.uiState.value.phase)
        assertEquals("Already recognized", recovery.snapshot.speech!!.text)
        recovery.writable = true
        model.retry(); runCurrent()
        assertEquals(1, calls)
        assertEquals("Already recognized", model.uiState.value.text)
        assertFalse(recorder.files.single().exists())
    }

    @Test
    fun cancelledOldTranscriptionCannotReplaceANewerRecording() = runTest {
        val recorder = FakeRecorder()
        val gate = CompletableDeferred<Unit>()
        val repo = object : SpeechRepository {
            override suspend fun transcribe(audio: File): String {
                withContext(NonCancellable) { gate.await() }
                return "Stale result"
            }
        }
        val model = model(recorder, repo)
        model.start(SpeechTarget.Chat); model.stopAndTranscribe(); runCurrent()
        model.cancel(); model.start(SpeechTarget.Memo)
        gate.complete(Unit); runCurrent()
        assertEquals(SpeechPhase.Recording, model.uiState.value.phase)
        assertEquals(SpeechTarget.Memo, model.uiState.value.target)
        assertEquals("", model.uiState.value.text)
        assertFalse(recorder.files.first().exists())
        assertTrue(recorder.files.last().exists())
        model.cancel()
    }

    @Test
    fun movingToBackgroundDiscardsAudioAndDoesNotUploadIt() = runTest {
        val recorder = FakeRecorder()
        val repo = object : SpeechRepository {
            override suspend fun transcribe(audio: File): String = error("Cancelled recording must not upload.")
        }
        val model = model(recorder, repo)
        model.start(SpeechTarget.Memo); model.stopForBackground(); runCurrent()
        assertFalse(recorder.recording)
        assertFalse(recorder.files.single().exists())
        assertEquals(SpeechIssue.Interrupted, model.uiState.value.error)
        assertEquals(SpeechPhase.Idle, model.uiState.value.phase)
    }

    @Test
    fun recordingAutomaticallyStopsAtTheTimeLimit() = runTest {
        val recorder = FakeRecorder()
        val repo = object : SpeechRepository {
            override suspend fun transcribe(audio: File) = "Limit reached"
        }
        val model = model(recorder, repo) { testScheduler.currentTime }
        model.start(SpeechTarget.Memo)
        advanceTimeBy(MAX_RECORDING_SECONDS * 1000L); runCurrent()
        assertEquals(1, recorder.stops)
        assertEquals(SpeechPhase.Review, model.uiState.value.phase)
        assertFalse(recorder.recording)
    }

    @Test
    fun permissionOrRecorderFailureLeavesTextInputAvailable() = runTest {
        val recorder = FakeRecorder().apply { issue = SpeechIssue.PermissionDenied }
        val repo = object : SpeechRepository {
            override suspend fun transcribe(audio: File): String = error("No audio expected.")
        }
        val model = model(recorder, repo)
        model.start(SpeechTarget.Chat)
        assertEquals(SpeechIssue.PermissionDenied, model.uiState.value.error)
        assertFalse(model.uiState.value.isBusy)
    }

    @Test
    fun clearingTheUserWorkspaceReleasesTheMicrophoneAndDeletesItsFile() = runTest {
        val recorder = FakeRecorder()
        val repo = object : SpeechRepository {
            override suspend fun transcribe(audio: File): String = error("No upload expected.")
        }
        val model = model(recorder, repo)
        model.start(SpeechTarget.Chat)
        stores.single().clear()
        assertFalse(recorder.recording)
        assertFalse(recorder.files.single().exists())
    }

    private inner class FakeRecorder : AudioRecorder {
        val files = mutableListOf<File>()
        var recording = false
        var stops = 0
        var issue: SpeechIssue? = null
        private var current: File? = null
        override fun start(onError: () -> Unit) {
            issue?.let { throw SpeechException(it) }
            current = temporary.newFile("clip-${files.size}.m4a").apply { writeText("audio") }.also { files.add(it) }
            recording = true
        }
        override fun stop(): File {
            recording = false; stops++
            return checkNotNull(current).also { current = null }
        }
        override fun cancel() { recording = false; current?.delete(); current = null }
    }
}
