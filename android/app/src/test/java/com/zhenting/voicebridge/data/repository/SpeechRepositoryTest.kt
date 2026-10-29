package com.zhenting.voicebridge.data.repository

import com.zhenting.voicebridge.data.model.MAX_AUDIO_BYTES
import com.zhenting.voicebridge.data.model.SpeechException
import com.zhenting.voicebridge.data.model.SpeechIssue
import com.zhenting.voicebridge.data.model.UserProfile
import com.zhenting.voicebridge.data.remote.RetrofitClient
import com.zhenting.voicebridge.data.session.SessionStore
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

class SpeechRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var sessions: SessionStore
    private lateinit var repository: SpeechRepository
    private val token = "a".repeat(43)

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build()
        sessions = SessionStore { 1000L }.also {
            it.save(token, 100_000, UserProfile("user-1", "Test User", null, null))
        }
        repository = RemoteSpeechRepository(
            RetrofitClient.create(server.url("/").toString(), client, sessions), sessions, "user-1"
        )
    }

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    private fun audio(name: String = "recording.m4a"): File = temporary.newFile(name).apply {
        writeText("audio-fixture-bytes")
    }

    @Test
    fun oneAudioPartIsSentThroughFlaskWithOnlyTheFlaskBearerToken() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"text":"  Hello from speech.  "}"""))
        val file = audio()
        assertEquals("Hello from speech.", repository.transcribe(file))
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/transcribe", request.path)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"file\"; filename=\"recording.m4a\""))
        assertTrue(body.contains("Content-Type: audio/mp4"))
        assertTrue(body.contains("audio-fixture-bytes"))
        assertFalse(body.contains(token))
        assertEquals(1, server.requestCount)
        assertTrue(file.exists())
    }

    @Test
    fun emptyOversizedOrUnsupportedRecordingsAreRejectedBeforeUpload() = runTest {
        val empty = temporary.newFile("empty.m4a")
        val huge = temporary.newFile("huge.m4a")
        RandomAccessFile(huge, "rw").use { it.setLength(MAX_AUDIO_BYTES + 1) }
        for ((file, expected) in listOf(empty to SpeechIssue.NoAudio,
            huge to SpeechIssue.TooLarge, audio("note.txt") to SpeechIssue.NoAudio)) {
            try { repository.transcribe(file); fail("Invalid audio was uploaded.") }
            catch (error: SpeechException) { assertEquals(expected, error.issue) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun blankTranscriptsAreNotPresentedAsRecognizedSpeech() = runTest {
        for (body in listOf("{}", """{"text":" "}""")) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
            try { repository.transcribe(audio("clip-${server.requestCount}.m4a")); fail("Empty text accepted.") }
            catch (error: SpeechException) { assertEquals(SpeechIssue.NoSpeech, error.issue) }
        }
    }

    @Test
    fun unauthorizedAudioUploadClearsTheExpiredFlaskSession() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"unauthorized"}"""))
        try { repository.transcribe(audio()); fail("Unauthorized speech request accepted.") }
        catch (error: SpeechException) { assertEquals(SpeechIssue.SessionExpired, error.issue) }
        assertNull(sessions.user.value)
    }

    @Test
    fun timeoutIsReportedWithoutRetryingTheUpload() = runTest {
        val shortClient = client.newBuilder().callTimeout(200, TimeUnit.MILLISECONDS).build()
        val shortRepository = RemoteSpeechRepository(
            RetrofitClient.create(server.url("/").toString(), shortClient, sessions), sessions, "user-1")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        try { shortRepository.transcribe(audio()); fail("Timed-out transcription accepted.") }
        catch (error: SpeechException) { assertEquals(SpeechIssue.Timeout, error.issue) }
        assertEquals(1, server.requestCount)
    }
}
