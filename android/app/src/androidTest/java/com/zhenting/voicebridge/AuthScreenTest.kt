package com.zhenting.voicebridge

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhenting.voicebridge.data.model.UserProfile
import com.zhenting.voicebridge.data.repository.AuthException
import com.zhenting.voicebridge.data.repository.AuthIssue
import com.zhenting.voicebridge.data.repository.AuthRepository
import com.zhenting.voicebridge.data.repository.GoogleLoginChallenge
import com.zhenting.voicebridge.ui.auth.AuthRoute
import com.zhenting.voicebridge.ui.auth.AuthViewModel
import com.zhenting.voicebridge.ui.theme.VoiceBridgeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun signInShowsTheUserAndSignOutReturnsToAnEnabledSignInButton() {
        val repository = FakeAuthRepository()
        var cleanupCalls = 0
        composeRule.setContent {
            val model: AuthViewModel = viewModel(factory = AuthViewModel.factory(repository))
            VoiceBridgeTheme {
                AuthRoute(
                    model,
                    getGoogleIdToken = { challenge ->
                        assertEquals("nonce-1", challenge.nonce)
                        "test-google-id-token"
                    },
                    clearGoogleSession = { cleanupCalls++ }
                )
            }
        }

        composeRule.onNodeWithTag("google_sign_in_button").performClick()
        composeRule.onNodeWithTag("signed_in_user").assertTextEquals("Test User")
        composeRule.onNodeWithTag("google_sign_in_button").assertDoesNotExist()
        composeRule.onNodeWithTag("sign_out_button").performClick()
        composeRule.onNodeWithTag("google_sign_in_button").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag("auth_error").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, cleanupCalls) }
    }

    @Test
    fun cancellingGoogleSignInAllowsAFreshAttemptWithoutSubmittingAToken() {
        val repository = FakeAuthRepository()
        composeRule.setContent {
            val model: AuthViewModel = viewModel(factory = AuthViewModel.factory(repository))
            VoiceBridgeTheme {
                AuthRoute(
                    model,
                    getGoogleIdToken = { throw AuthException(AuthIssue.Cancelled) },
                    clearGoogleSession = {}
                )
            }
        }

        repeat(2) {
            composeRule.onNodeWithTag("google_sign_in_button").performClick()
            composeRule.onNodeWithTag("google_sign_in_button").assertIsEnabled()
            composeRule.onNodeWithTag("auth_error").assertDoesNotExist()
        }
        composeRule.runOnIdle {
            assertEquals(2, repository.challengeCalls)
            assertEquals(0, repository.loginCalls)
        }
    }

    private class FakeAuthRepository : AuthRepository {
        override val user = MutableStateFlow<UserProfile?>(null)
        var challengeCalls = 0
        var loginCalls = 0

        override suspend fun createGoogleChallenge(): GoogleLoginChallenge {
            challengeCalls++
            return GoogleLoginChallenge("test-client", "nonce-$challengeCalls", Long.MAX_VALUE)
        }

        override suspend fun loginWithGoogle(challenge: GoogleLoginChallenge, idToken: String) {
            loginCalls++
            user.value = UserProfile("user-1", "Test User", "user@example.com", null)
        }

        override suspend fun refreshUser() = Unit
        override suspend fun logout() { user.value = null }
    }
}
