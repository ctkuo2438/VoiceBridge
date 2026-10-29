package com.zhenting.voicebridge.data.repository

import com.google.gson.JsonParser
import com.zhenting.voicebridge.data.model.UserProfile
import com.zhenting.voicebridge.data.remote.RetrofitClient
import com.zhenting.voicebridge.data.session.SessionStore
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class RemoteAuthRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var sessions: SessionStore
    private lateinit var repository: RemoteAuthRepository
    private var now = 1_000_000L
    private val nonce = "a".repeat(43)
    private val flaskToken = "b".repeat(43)
    private val profile = UserProfile("user-1", "Test User", "user@example.com", null)
    private val userJson = """{"id":"user-1","name":"Test User","email":"user@example.com","avatar_url":null}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build()
        sessions = SessionStore { now }
        repository = RemoteAuthRepository(
            RetrofitClient.create(server.url("/").toString(), client, sessions), sessions
        ) { now }
    }

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    private fun enqueue(body: String, status: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(status)
            .setHeader("Content-Type", "application/json").setBody(body))
    }

    private fun challenge() = GoogleLoginChallenge(
        "test.apps.googleusercontent.com", nonce, now + 300_000
    )

    private fun loginJson() =
        """{"access_token":"$flaskToken","token_type":"Bearer","expires_in":86400,"user":$userJson}"""

    private suspend fun expectIssue(issue: AuthIssue, block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected an authentication error.")
        } catch (error: AuthException) {
            assertEquals(issue, error.issue)
        }
    }

    @Test
    fun loginUsesTheBackendNonceAndOnlyTheFlaskTokenAuthenticatesLaterRequests() = runTest {
        enqueue("""{"client_id":"test.apps.googleusercontent.com","nonce":"$nonce","expires_in":300}""", 201)
        enqueue(loginJson(), 201)
        enqueue("""{"user":$userJson}""")
        enqueue("""{"message":"Logged out."}""")

        val challenge = repository.createGoogleChallenge()
        repository.loginWithGoogle(challenge, "test-google-id-token")
        assertEquals(flaskToken, sessions.accessToken())
        assertEquals(profile, repository.user.value)
        repository.refreshUser()
        repository.logout()
        assertNull(sessions.accessToken())
        assertNull(repository.user.value)

        val requests = List(4) { server.takeRequest(1, TimeUnit.SECONDS)!! }
        assertEquals(listOf("POST", "POST", "GET", "POST"), requests.map { it.method })
        assertEquals(
            listOf("/auth/google/challenge", "/auth/google", "/auth/me", "/auth/logout"),
            requests.map { it.path }
        )
        assertNull(requests[0].getHeader("Authorization"))
        assertNull(requests[1].getHeader("Authorization"))
        val login = JsonParser.parseString(requests[1].body.readUtf8()).asJsonObject
        assertEquals(nonce, login.get("nonce").asString)
        assertEquals("test-google-id-token", login.get("id_token").asString)
        assertEquals("Bearer $flaskToken", requests[2].getHeader("Authorization"))
        assertEquals("Bearer $flaskToken", requests[3].getHeader("Authorization"))
    }

    @Test
    fun invalidChallengesAreRejectedBeforeOpeningGoogleSignIn() = runTest {
        for (body in listOf("{}", "null", "not-json",
            """{"client_id":"android-client","nonce":"$nonce","expires_in":300}""",
            """{"client_id":"test.apps.googleusercontent.com","nonce":"short","expires_in":300}"""
        )) {
            enqueue(body, 201)
            expectIssue(AuthIssue.InvalidResponse) { repository.createGoogleChallenge() }
            assertNull(sessions.accessToken())
        }
    }

    @Test
    fun malformedSuccessResponsesCannotCreateALocalSession() = runTest {
        val invalid = listOf("{}", "null", "not-json") + listOf(
            "access_token", "token_type", "expires_in", "user"
        ).map { field ->
            JsonParser.parseString(loginJson()).asJsonObject.apply { remove(field) }.toString()
        } + JsonParser.parseString(loginJson()).asJsonObject.apply {
            addProperty("expires_in", Long.MAX_VALUE)
        }.toString()
        for (body in invalid) {
            enqueue(body, 201)
            expectIssue(AuthIssue.InvalidResponse) {
                repository.loginWithGoogle(challenge(), "test-google-id-token")
            }
            assertNull(sessions.accessToken())
            assertNull(repository.user.value)
        }
    }

    @Test
    fun expiredChallengeDoesNotSendTheGoogleTokenToTheBackend() = runTest {
        val challenge = challenge()
        now = challenge.expiresAtMillis
        expectIssue(AuthIssue.ChallengeExpired) {
            repository.loginWithGoogle(challenge, "test-google-id-token")
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun anExpiredServerChallengeIsReportedAndTheNextAttemptUsesANewNonce() = runTest {
        enqueue("""{"code":"google_login_expired","error":"Start sign-in again."}""", 401)
        expectIssue(AuthIssue.ChallengeExpired) {
            repository.loginWithGoogle(challenge(), "test-google-id-token")
        }
        val freshNonce = "c".repeat(43)
        enqueue("""{"client_id":"test.apps.googleusercontent.com","nonce":"$freshNonce","expires_in":300}""", 201)
        enqueue(loginJson(), 201)
        val fresh = repository.createGoogleChallenge()
        repository.loginWithGoogle(fresh, "fresh-google-id-token")
        assertEquals(freshNonce, fresh.nonce)
        assertEquals(profile, repository.user.value)
    }

    @Test
    fun aFailedServerLogoutKeepsTheSessionSoItCanBeRetried() = runTest {
        sessions.save(flaskToken, now + 86_400_000, profile)
        enqueue("""{"code":"unavailable"}""", 503)
        expectIssue(AuthIssue.ServerUnavailable) { repository.logout() }
        assertEquals(flaskToken, sessions.accessToken())
        assertEquals(profile, repository.user.value)
        enqueue("""{"message":"Logged out."}""")
        repository.logout()
        assertNull(sessions.accessToken())
    }

    @Test
    fun aProtected401ClearsTheSessionAndAnExpiredLogoutIsAlreadyComplete() = runTest {
        sessions.save(flaskToken, now + 86_400_000, profile)
        enqueue("""{"code":"unauthorized"}""", 401)
        expectIssue(AuthIssue.SessionExpired) { repository.refreshUser() }
        assertNull(sessions.accessToken())
        assertNull(repository.user.value)

        sessions.save(flaskToken, now + 86_400_000, profile)
        enqueue("""{"code":"unauthorized"}""", 401)
        repository.logout()
        assertNull(sessions.accessToken())
    }

    @Test
    fun localExpirationClearsTheProfileWithoutSendingAnExpiredBearerToken() = runTest {
        sessions.save(flaskToken, now + 1000, profile)
        now += 1000
        expectIssue(AuthIssue.SessionExpired) { repository.refreshUser() }
        assertNull(repository.user.value)
        assertEquals(0, server.requestCount)
    }
}
