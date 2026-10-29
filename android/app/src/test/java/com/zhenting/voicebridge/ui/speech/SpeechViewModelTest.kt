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

    private fun model(recorder: FakeRecorder, repo: SpeechRepository, clock: () -> Long = { 0L }): SpeechViewModel {
        val store = ViewModelStore().also { stores.add(it) }
        return ViewModelProvider(store, viewModelFactory {
            initializer { SpeechViewModel(recorder, repo, clock) }
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
