package com.zhenting.voicebridge

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhenting.voicebridge.data.audio.AndroidAudioPlayer
import com.zhenting.voicebridge.data.model.TtsException
import com.zhenting.voicebridge.data.model.TtsIssue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class AudioPlayerTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val player = AndroidAudioPlayer(instrumentation.targetContext)

    @After fun cleanup() {
        scope.cancel()
        instrumentation.runOnMainSync { player.stop() }
    }

    @Test fun nativePlayerStartsAndCompletesAWavFromMemory() {
        val started = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(1)
        scope.launch {
            try { player.play(wav(300)) { started.set(true) } }
            catch (error: Throwable) { failure.set(error) }
            finally { done.countDown() }
        }
        assertTrue("Playback did not finish", done.await(10, TimeUnit.SECONDS))
        assertNull(failure.get())
        assertTrue(started.get())
    }

    @Test fun stopInterruptsPlaybackAndThePlayerCanBeUsedAgain() {
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        scope.launch {
            try { player.play(wav(5000)) { started.countDown() } }
            catch (error: Throwable) { failure.set(error) }
            finally { done.countDown() }
        }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { player.stop() }
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals(TtsIssue.Interrupted, (failure.get() as TtsException).issue)

        val replay = CountDownLatch(1)
        failure.set(null)
        scope.launch {
            try { player.play(wav(200)) {} }
            catch (error: Throwable) { failure.set(error) }
            finally { replay.countDown() }
        }
        assertTrue(replay.await(10, TimeUnit.SECONDS))
        assertNull(failure.get())
    }

    private fun wav(milliseconds: Int): ByteArray {
        val dataBytes = 24000 * milliseconds / 1000 * 2
        return ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes)
            put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(24000); putInt(48000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes); put(ByteArray(dataBytes))
        }.array()
    }
}
