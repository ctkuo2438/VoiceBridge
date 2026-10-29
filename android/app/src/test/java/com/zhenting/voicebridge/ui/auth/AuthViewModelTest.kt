package com.zhenting.voicebridge.ui.auth

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.zhenting.voicebridge.MainDispatcherRule
import com.zhenting.voicebridge.data.model.UserProfile
import com.zhenting.voicebridge.data.repository.AuthException
import com.zhenting.voicebridge.data.repository.AuthIssue
import com.zhenting.voicebridge.data.repository.AuthRepository
import com.zhenting.voicebridge.data.repository.GoogleLoginChallenge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    private val stores = mutableListOf<ViewModelStore>()

    @After
    fun tearDown() { stores.forEach { it.clear() } }

    private fun viewModel(repository: FakeAuthRepository): AuthViewModel {
        val store = ViewModelStore().also { stores.add(it) }
        return ViewModelProvider(store, AuthViewModel.factory(repository))[AuthViewModel::class.java]
    }

    @Test
    fun signingInPassesTheChallengeToGoogleAndIgnoresRepeatedTaps() = runTest {
        val repository = FakeAuthRepository()
        val model = viewModel(repository)
        val googleToken = CompletableDeferred<String>()
        val first = launch {
            model.signIn { challenge ->
                assertEquals("nonce-1", challenge.nonce)
                googleToken.await()
            }
        }
        runCurrent()
        assertEquals(AuthPhase.GoogleSignIn, model.uiState.value.phase)
        model.signIn { throw AssertionError("Repeated sign-in must be ignored.") }
        assertEquals(1, repository.challengeCalls)
        googleToken.complete("google-token")
        first.join()
        advanceUntilIdle()
        assertEquals("google-token", repository.lastToken)
        assertEquals("Test User", model.uiState.value.user!!.name)
        assertFalse(model.uiState.value.isLoading)
        assertNull(model.uiState.value.error)
    }

    @Test
    fun leavingTheScreenCancelsSignInAndTheNextAttemptGetsANewChallenge() = runTest {
        val repository = FakeAuthRepository()
        val model = viewModel(repository)
        val attempt = launch { model.signIn { awaitCancellation() } }
        runCurrent()
        assertTrue(model.uiState.value.isLoading)
        attempt.cancelAndJoin()
        assertFalse(model.uiState.value.isLoading)
        assertNull(model.uiState.value.error)
        model.signIn {
            assertEquals("nonce-2", it.nonce)
            "fresh-google-token"
        }
        advanceUntilIdle()
        assertEquals(2, repository.challengeCalls)
        assertEquals("fresh-google-token", repository.lastToken)
    }

    @Test
    fun dismissingTheGooglePickerReturnsToAnEnabledSignInState() = runTest {
        val repository = FakeAuthRepository()
        val model = viewModel(repository)
        model.signIn { throw AuthException(AuthIssue.Cancelled) }
        assertFalse(model.uiState.value.isLoading)
        assertNull(model.uiState.value.user)
        assertNull(model.uiState.value.error)
        assertEquals(0, repository.loginCalls)
    }

    @Test
    fun failedLogoutKeepsTheUserAndOnlySuccessfulLogoutClearsGooglePreferences() = runTest {
        val repository = FakeAuthRepository()
        repository.user.value = repository.profile
        val model = viewModel(repository)
        repository.logoutIssue = AuthIssue.ServerUnavailable
        var cleanupCalls = 0
        model.signOut { cleanupCalls++ }
        advanceUntilIdle()
        assertEquals(repository.profile, model.uiState.value.user)
        assertEquals(AuthIssue.ServerUnavailable, model.uiState.value.error)
        assertEquals(0, cleanupCalls)
        repository.logoutIssue = null
        model.signOut { cleanupCalls++ }
        advanceUntilIdle()
        assertNull(model.uiState.value.user)
        assertNull(model.uiState.value.error)
        assertEquals(1, cleanupCalls)
    }

    @Test
    fun sessionInvalidationReturnsToSignInWithAnExpirationMessage() = runTest {
        val repository = FakeAuthRepository()
        repository.user.value = repository.profile
        val model = viewModel(repository)
        runCurrent()
        repository.user.value = null
        runCurrent()
        assertNull(model.uiState.value.user)
        assertEquals(AuthIssue.SessionExpired, model.uiState.value.error)
    }

    private class FakeAuthRepository : AuthRepository {
        val profile = UserProfile("user-1", "Test User", null, null)
        override val user = MutableStateFlow<UserProfile?>(null)
        var challengeCalls = 0
        var loginCalls = 0
        var lastToken: String? = null
        var logoutIssue: AuthIssue? = null

        override suspend fun createGoogleChallenge(): GoogleLoginChallenge {
            challengeCalls++
            return GoogleLoginChallenge("test-client", "nonce-$challengeCalls", Long.MAX_VALUE)
        }

        override suspend fun loginWithGoogle(challenge: GoogleLoginChallenge, idToken: String) {
            loginCalls++
            lastToken = idToken
            user.value = profile
        }

        override suspend fun refreshUser() = Unit

        override suspend fun logout() {
            logoutIssue?.let { throw AuthException(it) }
            user.value = null
        }
    }
}
