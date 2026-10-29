package com.zhenting.voicebridge.data.remote

import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class VoiceBridgeAuthApiTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: VoiceBridgeApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .callTimeout(3, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        api = RetrofitClient.create(server.url("/").toString(), client)
    }

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun challengeUsesThePublicPostEndpointAndReadsFlaskFieldNames() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"client_id":"test-web-client-id","nonce":"test-nonce","expires_in":300}"""
                )
        )

        val response = api.createGoogleChallenge()

        assertEquals(201, response.code())
        assertTrue(response.isSuccessful)
        val challenge = response.body()
        assertNotNull(challenge)
        assertEquals("test-web-client-id", challenge!!.clientId)
        assertEquals("test-nonce", challenge.nonce)
        assertEquals(300L, challenge.expiresIn)

        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("POST", request!!.method)
        assertEquals("/auth/google/challenge", request.path)
        assertNull(request.getHeader("Authorization"))
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun googleLoginSendsIdTokenAndNonceAsJsonAndReadsTheFlaskSession() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "access_token": "test-flask-access-token",
                      "token_type": "Bearer",
                      "expires_in": 86400,
                      "user": {
                        "id": "test-user-id",
                        "name": "Test User",
                        "email": "user@example.com",
                        "avatar_url": "https://example.com/avatar.png"
                      }
                    }
                    """.trimIndent()
                )
        )

        val response = api.loginWithGoogle(
            GoogleLoginRequest(idToken = "test-google-id-token", nonce = "test-nonce")
        )

        assertEquals(201, response.code())
        assertTrue(response.isSuccessful)
        val session = response.body()
        assertNotNull(session)
        assertEquals("test-flask-access-token", session!!.accessToken)
        assertEquals("Bearer", session.tokenType)
        assertEquals(86400L, session.expiresIn)
        val user = session.user
        assertNotNull(user)
        assertEquals("test-user-id", user!!.id)
        assertEquals("Test User", user.name)
        assertEquals("user@example.com", user.email)
        assertEquals("https://example.com/avatar.png", user.avatarUrl)

        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("POST", request!!.method)
        assertEquals("/auth/google", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertNull(request.getHeader("Authorization"))
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals(setOf("id_token", "nonce"), json.keySet())
        assertEquals("test-google-id-token", json.get("id_token").asString)
        assertEquals("test-nonce", json.get("nonce").asString)
    }

    @Test
    fun expiredChallengeKeepsTheHttpErrorForTheRepositoryToHandle() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"code":"google_login_expired","error":"Start Google sign-in again."}"""
                )
        )

        val response = api.loginWithGoogle(
            GoogleLoginRequest(idToken = "test-google-id-token", nonce = "expired-nonce")
        )

        assertEquals(401, response.code())
        assertFalse(response.isSuccessful)
        assertNull(response.body())
        response.errorBody().use { body ->
            assertNotNull(body)
            val json = JsonParser.parseString(body!!.string()).asJsonObject
            assertEquals("google_login_expired", json.get("code").asString)
        }
    }
}
