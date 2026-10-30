package com.zhenting.voicebridge.data.repository

import com.google.gson.JsonParser
import com.zhenting.voicebridge.data.model.*
import com.zhenting.voicebridge.data.remote.RetrofitClient
import com.zhenting.voicebridge.data.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

class TtsRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var sessions: SessionStore
    private lateinit var repository: TtsRepository
    private val token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEifQ.signature"
    private val profile = UserProfile("user-1", "Test", null, null)

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        sessions = SessionStore { 1000L }.also { it.save(token, 100_000, profile) }
        repository = RemoteTtsRepository(RetrofitClient.create(server.url("/").toString(), client, sessions), sessions, "user-1")
    }
    @After fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    private fun wav(): ByteArray = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(40); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(24000); putInt(48000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(4); putInt(0)
    }.array()

    private fun audioResponse() = MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wav()))
    private suspend fun expectIssue(issue: TtsIssue, block: suspend () -> Unit) {
        try { block(); fail("Expected TTS failure") }
        catch (error: TtsException) { assertEquals(issue, error.issue) }
    }

    @Test fun binaryAudioUsesOnlyTheFlaskJwtAndPostsTextOnce() = runTest {
        server.enqueue(audioResponse())
        assertArrayEquals(wav(), repository.synthesize("  Read my note.  "))
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/tts", request.path)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        assertNull(request.getHeader("x-goog-api-key"))
        assertEquals("Read my note.", JsonParser.parseString(request.body.readUtf8()).asJsonObject.get("text").asString)
        assertEquals(1, server.requestCount)
    }

    @Test fun invalidInputAndAChangedOwnerCannotUseTheNetwork() = runTest {
        for (text in listOf(" ", "a".repeat(4001))) expectIssue(TtsIssue.InvalidText) { repository.synthesize(text) }
        sessions.save("new-token", 100_000, profile.copy(id = "different-user"))
        expectIssue(TtsIssue.SessionExpired) { repository.synthesize("Private note") }
        assertEquals(0, server.requestCount)
    }

    @Test fun errorsAreMappedWithoutRetryingOrLoggingOutOnQuotaFailure() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"code":"gemini_rate_limit"}"""))
        expectIssue(TtsIssue.RateLimited) { repository.synthesize("Hello") }
        assertNotNull(sessions.user.value)
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"code":"gemini_auth_failed"}"""))
        expectIssue(TtsIssue.Unavailable) { repository.synthesize("Hello") }
        assertNotNull(sessions.user.value)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"unauthorized"}"""))
        expectIssue(TtsIssue.SessionExpired) { repository.synthesize("Hello") }
        assertNull(sessions.user.value)
        assertEquals(3, server.requestCount)
    }

    @Test fun nonAudioEmptyCorruptAndOversizedResponsesNeverReachPlayback() = runTest {
        for (response in listOf(
            MockResponse().setHeader("Content-Type", "application/json").setBody("{}"),
            MockResponse().setHeader("Content-Type", "audio/wav").setBody(""),
            MockResponse().setHeader("Content-Type", "audio/wav").setBody("a".repeat(44)),
            audioResponse().setHeader("Content-Length", MAX_TTS_AUDIO_BYTES + 1)
        )) {
            server.enqueue(response)
            expectIssue(TtsIssue.InvalidAudio) { repository.synthesize("Hello") }
        }
    }

    @Test fun aLateResponseCannotCrossIntoANewerLogin() = runTest {
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(release.await(3, TimeUnit.SECONDS))
                return audioResponse()
            }
        }
        val pending = async(Dispatchers.Default) { runCatching { repository.synthesize("Private note") } }
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        sessions.save("new-token", 100_000, profile.copy(id = "different-user"))
        release.countDown()
        assertEquals(TtsIssue.SessionExpired, (pending.await().exceptionOrNull() as TtsException).issue)
        assertEquals("different-user", sessions.user.value?.id)
    }

    @Test fun cancellationStopsWaitingForAudioWithoutRetrying() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async(Dispatchers.Default) { repository.synthesize("Hello") }
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test fun chunksRespectUnicodeAndPreserveTheFullText() {
        val text = "word ".repeat(2000).trim()
        val chunks = speechChunks(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { chatCharacterCount(it) in 1..4000 })
        assertEquals(text, chunks.joinToString(" "))
        val emoji = "a".repeat(3999) + "\uD83D\uDE00" + "z"
        val emojiChunks = speechChunks(emoji)
        assertEquals(emoji, emojiChunks.joinToString(""))
        assertEquals(4000, chatCharacterCount(emojiChunks.first()))
        assertEquals("z", emojiChunks.last())
    }
}
