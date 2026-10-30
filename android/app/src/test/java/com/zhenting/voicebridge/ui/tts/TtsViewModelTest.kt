package com.zhenting.voicebridge.ui.tts

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhenting.voicebridge.MainDispatcherRule
import com.zhenting.voicebridge.data.audio.AudioPlayer
import com.zhenting.voicebridge.data.model.TtsException
import com.zhenting.voicebridge.data.model.TtsIssue
import com.zhenting.voicebridge.data.repository.TtsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TtsViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private val stores = mutableListOf<ViewModelStore>()
    @After fun clear() { stores.forEach { it.clear() } }

    private fun model(repository: TtsRepository, player: FakePlayer): TtsViewModel {
        val store = ViewModelStore().also(stores::add)
        return ViewModelProvider(store, viewModelFactory {
            initializer { TtsViewModel(repository, player) }
        })[TtsViewModel::class.java]
    }

    @Test
    fun readAloudOnlyStartsOnRequestAndCompletionReturnsToIdle() = runTest {
        val calls = mutableListOf<String>()
        val player = FakePlayer()
        val vm = model(repository { calls.add(it); byteArrayOf(1) }, player)
        runCurrent()
        assertTrue(calls.isEmpty())
        vm.speak("memo:one", "My memo")
        assertEquals(TtsPhase.Generating, vm.uiState.value.phase)
        runCurrent()
        assertEquals(listOf("My memo"), calls)
        assertEquals(TtsPhase.Playing, vm.uiState.value.phase)
        player.complete()
        runCurrent()
        assertEquals(TtsUiState(), vm.uiState.value)
    }

    @Test
    fun longTextIsReadInOrderAndStopPreventsTheNextRequest() = runTest {
        val calls = mutableListOf<String>()
        val player = FakePlayer()
        val vm = model(repository { calls.add(it); byteArrayOf(1) }, player)
        vm.speak("chat:one", "a".repeat(4000) + "b".repeat(4000) + "c")
        runCurrent()
        assertEquals(1, calls.size)
        assertEquals(3, vm.uiState.value.totalParts)
        player.complete(); runCurrent()
        assertEquals(listOf("a".repeat(4000), "b".repeat(4000)), calls)
        assertEquals(2, vm.uiState.value.part)
        vm.stop(); runCurrent()
        assertEquals(2, calls.size)
        assertEquals(TtsUiState(), vm.uiState.value)
        assertEquals(0, player.active)
        assertEquals(1, player.maximumActive)
    }

    @Test
    fun cancelledOldGenerationCannotPlayOrStopANewerItem() = runTest {
        val gate = CompletableDeferred<Unit>()
        val player = FakePlayer()
        val vm = model(repository { text ->
            if (text == "old") withContext(NonCancellable) { gate.await() }
            byteArrayOf(if (text == "old") 1 else 2)
        }, player)
        vm.speak("memo:old", "old"); runCurrent()
        vm.speak("chat:new", "new"); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("chat:new", vm.uiState.value.itemId)
        assertEquals(TtsPhase.Playing, vm.uiState.value.phase)
        assertEquals(listOf(2), player.started)
        assertEquals(1, player.active)
    }

    @Test
    fun switchingItemsReplacesPlaybackAndClearingTheWorkspaceStopsIt() = runTest {
        val player = FakePlayer()
        val vm = model(repository { byteArrayOf(1) }, player)
        vm.speak("memo:one", "first"); runCurrent()
        vm.speak("memo:two", "second"); runCurrent()
        assertEquals("memo:two", vm.uiState.value.itemId)
        assertEquals(1, player.active)
        stores.single().clear(); runCurrent()
        assertEquals(0, player.active)
        assertEquals(TtsUiState(), vm.uiState.value)
    }

    @Test
    fun failuresAreShownForTheirItemWithoutAutomaticRetries() = runTest {
        var calls = 0
        val vm = model(repository { calls++; throw TtsException(TtsIssue.RateLimited) }, FakePlayer())
        vm.speak("chat:one", "Read me"); runCurrent()
        assertEquals(1, calls)
        assertEquals(TtsIssue.RateLimited, vm.uiState.value.error)
        assertEquals(TtsPhase.Idle, vm.uiState.value.phase)
        assertEquals("chat:one", vm.uiState.value.itemId)
    }

    @Test
    fun emptyTextDoesNotRequestAudio() = runTest {
        val vm = model(repository { error("No request expected") }, FakePlayer())
        vm.speak("memo:one", " \n "); runCurrent()
        assertEquals(TtsIssue.InvalidText, vm.uiState.value.error)
    }

    private fun repository(block: suspend (String) -> ByteArray) = object : TtsRepository {
        override suspend fun synthesize(text: String) = block(text)
    }

    private class FakePlayer : AudioPlayer {
        var active = 0
        var maximumActive = 0
        val started = mutableListOf<Int>()
        private var gate: CompletableDeferred<Unit>? = null
        override suspend fun play(audio: ByteArray, onStarted: () -> Unit) {
            val completion = CompletableDeferred<Unit>().also { gate = it }
            active++
            maximumActive = maxOf(maximumActive, active)
            started.add(audio.first().toInt())
            onStarted()
            try { completion.await() } finally { active-- }
        }
        override fun stop() = Unit
        fun complete() { checkNotNull(gate).complete(Unit) }
    }
}
