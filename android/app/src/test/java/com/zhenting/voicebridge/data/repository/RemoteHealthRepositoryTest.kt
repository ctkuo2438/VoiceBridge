package com.zhenting.voicebridge.data.repository

import com.zhenting.voicebridge.data.remote.RetrofitClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class RemoteHealthRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .callTimeout(3, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    private fun repository(httpClient: OkHttpClient = client) =
        RemoteHealthRepository(RetrofitClient.create(server.url("/").toString(), httpClient))

    @Test
    fun healthyResponseUsesOnlyThePublicHealthEndpoint() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"status":"ok"}""")
        )

        assertSame(HealthCheckResult.Healthy, repository().checkConnection())

        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("GET", request!!.method)
        assertEquals("/health", request.path)
        assertNull(request.getHeader("Authorization"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun httpFailureDoesNotExposeTheServerErrorBody() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(503).setBody("""{"error":"private-server-detail"}""")
        )

        assertEquals(HealthCheckResult.HttpError(503), repository().checkConnection())
    }

    @Test
    fun invalidOrEmptyResponsesAreNotTreatedAsHealthy() = runTest {
        for (body in listOf("{}", """{"status":"down"}""", "null", "[]", "not-json")) {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            )
            assertSame(HealthCheckResult.InvalidResponse, repository().checkConnection())
        }
        server.enqueue(MockResponse().setResponseCode(204))
        assertSame(HealthCheckResult.InvalidResponse, repository().checkConnection())
    }

    @Test
    fun aTimeoutReturnsAnUnreachableResult() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val shortTimeout = client.newBuilder().callTimeout(200, TimeUnit.MILLISECONDS).build()

        assertSame(HealthCheckResult.Unreachable, repository(shortTimeout).checkConnection())
    }
}
