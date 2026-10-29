package com.zhenting.voicebridge.data.session

import com.zhenting.voicebridge.data.model.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

class SessionCredentials internal constructor(internal val token: String, val userId: String)

class SessionChangedException : IOException("The request belongs to a different session.")

/** Keeps only the Flask session in memory; Google ID tokens are never stored. */
class SessionStore(private val nowMillis: () -> Long = System::currentTimeMillis) {
    private class Session(val token: String, val expiresAtMillis: Long)

    private var session: Session? = null
    private val _user = MutableStateFlow<UserProfile?>(null)
    val user = _user.asStateFlow()

    @Synchronized
    fun save(token: String, expiresAtMillis: Long, user: UserProfile) {
        require(token.isNotBlank() && expiresAtMillis > nowMillis())
        session = Session(token, expiresAtMillis)
        _user.value = user
    }

    @Synchronized
    fun accessToken(): String? {
        val current = session ?: return null
        if (current.expiresAtMillis <= nowMillis()) {
            clearIfMatches(current.token)
            return null
        }
        return current.token
    }

    @Synchronized
    fun credentials(userId: String): SessionCredentials {
        val token = accessToken()
        if (token == null || _user.value?.id != userId) throw SessionChangedException()
        return SessionCredentials(token, userId)
    }

    @Synchronized
    fun matches(credentials: SessionCredentials): Boolean =
        accessToken() == credentials.token && _user.value?.id == credentials.userId

    @Synchronized
    fun updateUserIfMatches(token: String, user: UserProfile): Boolean {
        if (accessToken() != token) return false
        _user.value = user
        return true
    }

    @Synchronized
    fun clearIfMatches(token: String) {
        // A response from an older session must not remove a newer login.
        if (session?.token == token) {
            session = null
            _user.value = null
        }
    }
}
