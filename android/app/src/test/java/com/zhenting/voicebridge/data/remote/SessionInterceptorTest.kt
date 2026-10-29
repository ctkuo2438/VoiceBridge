package com.zhenting.voicebridge.data.remote

import com.zhenting.voicebridge.data.model.UserProfile
import com.zhenting.voicebridge.data.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SessionInterceptorTest {
    private val profile = UserProfile("user-1", "Test User", null, null)

    @Test
    fun credentialsAreNotSentToAnotherOriginAndIts401CannotClearTheSession() {
        val backend = MockWebServer()
        val other = MockWebServer()
        backend.start()
        other.start()
        val sessions = SessionStore { 1000L }
        val token = "a".repeat(43)
        sessions.save(token, 10_000, profile)
        val client = OkHttpClient.Builder()
            .callTimeout(3, TimeUnit.SECONDS)
            .addInterceptor(SessionInterceptor(sessions, backend.url("/")))
            .build()
        try {
            other.enqueue(MockResponse().setResponseCode(401))
            client.newCall(Request.Builder().url(other.url("/private")).build())
                .execute().use { assertEquals(401, it.code) }
            assertNull(other.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            assertEquals(token, sessions.accessToken())
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            backend.shutdown()
            other.shutdown()
        }
    }

    @Test
    fun aLate401FromAnOlderRequestDoesNotRemoveANewerLogin() = runTest {
        val server = MockWebServer()
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                received.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                return MockResponse().setResponseCode(401)
            }
        }
        server.start()
        val sessions = SessionStore { 1000L }
        sessions.save("a".repeat(43), 10_000, profile)
        val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS)
            .addInterceptor(SessionInterceptor(sessions, server.url("/"))).build()
        try {
            val request = async(Dispatchers.IO) {
                client.newCall(Request.Builder().url(server.url("/auth/me")).build())
                    .execute().use { it.code }
            }
            assertTrue(received.await(3, TimeUnit.SECONDS))
            sessions.save("b".repeat(43), 10_000, profile.copy(id = "new-user"))
            release.countDown()
            assertEquals(401, request.await())
            assertEquals("b".repeat(43), sessions.accessToken())
            assertEquals("new-user", sessions.user.value!!.id)
        } finally {
            release.countDown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
