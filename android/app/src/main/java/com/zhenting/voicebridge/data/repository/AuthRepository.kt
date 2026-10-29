package com.zhenting.voicebridge.data.repository

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import com.zhenting.voicebridge.data.model.UserProfile
import com.zhenting.voicebridge.data.remote.ApiErrorResponse
import com.zhenting.voicebridge.data.remote.GoogleLoginRequest
import com.zhenting.voicebridge.data.remote.UserResponse
import com.zhenting.voicebridge.data.remote.VoiceBridgeApi
import com.zhenting.voicebridge.data.session.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import retrofit2.Response
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException

// 1. Request sign-in challenge and get 2. Client ID + nonce from Flask
data class GoogleLoginChallenge(
    val clientId: String,
    val nonce: String,
    val expiresAtMillis: Long
)

enum class AuthIssue {
    Network, Timeout, InvalidResponse, ChallengeExpired, SignInRejected,
    SessionExpired, ServerUnavailable, Cancelled, GoogleUnavailable, GoogleCleanupFailed
}

class AuthException(val issue: AuthIssue) : Exception(issue.name)

interface AuthRepository {
    val user: StateFlow<UserProfile?>
    suspend fun createGoogleChallenge(): GoogleLoginChallenge
    suspend fun loginWithGoogle(challenge: GoogleLoginChallenge, idToken: String)
    suspend fun refreshUser()
    suspend fun logout()
}

class RemoteAuthRepository(
    private val api: VoiceBridgeApi,
    private val sessionStore: SessionStore,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : AuthRepository {
    override val user = sessionStore.user
    private val gson = Gson()
    private val tokenPattern = Regex("[A-Za-z0-9_-]{43}")

    override suspend fun createGoogleChallenge(): GoogleLoginChallenge {
        val response = request { api.createGoogleChallenge() }
        val clientId = response.clientId
        val nonce = response.nonce
        if (clientId.isNullOrBlank() || !clientId.endsWith(".apps.googleusercontent.com") ||
            nonce == null || !tokenPattern.matches(nonce)
        ) throw AuthException(AuthIssue.InvalidResponse)
        return GoogleLoginChallenge(clientId, nonce, expiration(response.expiresIn))
    }

    // 5. POST /auth/google (ID token + nonce) to Flask, and get Flask access token,
    //  then save to sessionStore
    override suspend fun loginWithGoogle(challenge: GoogleLoginChallenge, idToken: String) {
        if (challenge.expiresAtMillis <= nowMillis()) {
            throw AuthException(AuthIssue.ChallengeExpired)
        }
        if (idToken.isBlank() || idToken.length > 8192) {
            throw AuthException(AuthIssue.SignInRejected)
        }
        val response = request {
            api.loginWithGoogle(GoogleLoginRequest(idToken, challenge.nonce))
        }
        val token = response.accessToken
        if (token == null || !tokenPattern.matches(token) ||
            !response.tokenType.equals("Bearer", ignoreCase = true)
        ) throw AuthException(AuthIssue.InvalidResponse)
        val profile = profile(response.user)
        val expiresAt = expiration(response.expiresIn)
        currentCoroutineContext().ensureActive()
        sessionStore.save(token, expiresAt, profile)
    }

    override suspend fun refreshUser() {
        val token = sessionStore.accessToken() ?: throw AuthException(AuthIssue.SessionExpired)
        val response = request(AuthIssue.SessionExpired) { api.getCurrentUser() }
        currentCoroutineContext().ensureActive()
        if (!sessionStore.updateUserIfMatches(token, profile(response.user))) {
            throw AuthException(AuthIssue.SessionExpired)
        }
    }

    override suspend fun logout() {
        val token = sessionStore.accessToken() ?: return
        try {
            request(AuthIssue.SessionExpired) { api.logout() }
        } catch (error: AuthException) {
            // A revoked or expired session is already signed out on the server.
            if (error.issue != AuthIssue.SessionExpired) throw error
        }
        sessionStore.clearIfMatches(token)
    }

    private fun profile(response: UserResponse?): UserProfile {
        val data = response ?: throw AuthException(AuthIssue.InvalidResponse)
        val id = data.id
        val name = data.name
        if (id.isNullOrBlank() || name.isNullOrBlank()) {
            throw AuthException(AuthIssue.InvalidResponse)
        }
        return UserProfile(id, name, data.email, data.avatarUrl)
    }

    private fun expiration(seconds: Long?): Long {
        if (seconds == null || seconds <= 0) throw AuthException(AuthIssue.InvalidResponse)
        return try {
            Math.addExact(nowMillis(), Math.multiplyExact(seconds, 1000L))
        } catch (_: ArithmeticException) {
            throw AuthException(AuthIssue.InvalidResponse)
        }
    }

    private suspend fun <T : Any> request(
        unauthorized: AuthIssue = AuthIssue.SignInRejected,
        call: suspend () -> Response<T>
    ): T = try {
        val response = call()
        if (!response.isSuccessful) {
            val code = response.errorBody()?.use { body ->
                try {
                    gson.fromJson(body.charStream(), ApiErrorResponse::class.java)?.code
                } catch (_: JsonParseException) {
                    null
                }
            }
            throw AuthException(
                when {
                    code == "google_login_expired" -> AuthIssue.ChallengeExpired
                    response.code() == 401 -> unauthorized
                    response.code() >= 500 -> AuthIssue.ServerUnavailable
                    else -> AuthIssue.SignInRejected
                }
            )
        }
        response.body() ?: throw AuthException(AuthIssue.InvalidResponse)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: InterruptedIOException) {
        currentCoroutineContext().ensureActive()
        throw AuthException(AuthIssue.Timeout)
    } catch (_: JsonParseException) {
        throw AuthException(AuthIssue.InvalidResponse)
    } catch (error: IOException) {
        currentCoroutineContext().ensureActive()
        throw AuthException(
            if (error is MalformedJsonException || error is EOFException) {
                AuthIssue.InvalidResponse
            } else AuthIssue.Network
        )
    }
}
