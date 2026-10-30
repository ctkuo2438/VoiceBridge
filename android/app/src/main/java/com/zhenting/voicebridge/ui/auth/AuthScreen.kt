package com.zhenting.voicebridge.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import com.zhenting.voicebridge.ui.components.BrandMark
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhenting.voicebridge.R
import com.zhenting.voicebridge.data.repository.AuthIssue
import com.zhenting.voicebridge.data.repository.GoogleLoginChallenge
import com.zhenting.voicebridge.ui.theme.VoiceBridgeTheme
import kotlinx.coroutines.launch

@Composable
fun AuthRoute(
    viewModel: AuthViewModel,
    getGoogleIdToken: suspend (GoogleLoginChallenge) -> String,
    clearGoogleSession: suspend () -> Unit,
    modifier: Modifier = Modifier,
    onConnectionTest: (() -> Unit)? = null,
    signedInContent: (@Composable (AuthUiState, () -> Unit, Modifier) -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshUser() }
    if (state.user != null && signedInContent != null) {
        signedInContent(state, { scope.launch { viewModel.signOut(clearGoogleSession) } }, modifier)
        return
    }
    AuthScreen(
        uiState = state,
        onSignIn = { scope.launch { viewModel.signIn(getGoogleIdToken) } },
        onSignOut = { scope.launch { viewModel.signOut(clearGoogleSession) } },
        onDismissError = viewModel::dismissError,
        onRetry = viewModel::refreshUser,
        modifier = modifier,
        onConnectionTest = onConnectionTest
    )
}

@Composable
fun AuthScreen(
    uiState: AuthUiState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onDismissError: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onConnectionTest: (() -> Unit)? = null
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BrandMark(68.dp)
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.auth_description), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Card(Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val user = uiState.user
                Text(
                    stringResource(if (user == null) R.string.auth_welcome else R.string.auth_signed_in),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag("auth_status").semantics {
                        liveRegion = LiveRegionMode.Polite
                    }
                )
                if (user == null) {
                    Text(stringResource(R.string.auth_sign_in_description), textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Image(
                        painter = painterResource(R.drawable.google_sign_in),
                        contentDescription = stringResource(R.string.auth_google_sign_in),
                        modifier = Modifier.widthIn(max = 288.dp).fillMaxWidth().height(60.dp)
                            .alpha(if (uiState.isLoading) 0.5f else 1f)
                            .testTag("google_sign_in_button")
                            .clickable(
                                enabled = !uiState.isLoading,
                                role = Role.Button,
                                onClick = onSignIn
                            )
                    )
                } else {
                    Text(
                        user.name,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.testTag("signed_in_user")
                    )
                    user.email?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    OutlinedButton(
                        onClick = onSignOut,
                        enabled = !uiState.isLoading,
                        modifier = Modifier.fillMaxWidth().testTag("sign_out_button")
                    ) { Text(stringResource(R.string.auth_sign_out)) }
                }
                if (uiState.isLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        stringResource(
                            when (uiState.phase) {
                                AuthPhase.PreparingSignIn -> R.string.auth_preparing
                                AuthPhase.GoogleSignIn -> R.string.auth_choosing_account
                                AuthPhase.Verifying -> R.string.auth_verifying
                                AuthPhase.Refreshing -> R.string.auth_refreshing
                                AuthPhase.SigningOut -> R.string.auth_signing_out
                                AuthPhase.Idle -> R.string.auth_preparing
                            }
                        ),
                        modifier = Modifier.testTag("auth_progress")
                    )
                }
            }
        }
        Text(stringResource(R.string.auth_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp))
        uiState.error?.let { issue ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(issue.messageResource()),
                        modifier = Modifier.testTag("auth_error").semantics {
                            liveRegion = LiveRegionMode.Polite
                        }
                    )
                    if (uiState.user != null) {
                        TextButton(onClick = onRetry, enabled = !uiState.isLoading) {
                            Text(stringResource(R.string.auth_retry))
                        }
                    }
                    TextButton(onClick = onDismissError) {
                        Text(stringResource(R.string.auth_dismiss))
                    }
                }
            }
        }
        if (onConnectionTest != null) {
            TextButton(onClick = onConnectionTest, enabled = !uiState.isLoading) {
                Text(stringResource(R.string.auth_connection_test))
            }
        }
    }
}

internal fun AuthIssue.messageResource(): Int = when (this) {
    AuthIssue.Network -> R.string.auth_network_error
    AuthIssue.Timeout -> R.string.auth_timeout_error
    AuthIssue.InvalidResponse -> R.string.auth_response_error
    AuthIssue.ChallengeExpired -> R.string.auth_challenge_expired
    AuthIssue.SignInRejected -> R.string.auth_rejected
    AuthIssue.SessionExpired -> R.string.auth_session_expired
    AuthIssue.ServerUnavailable -> R.string.auth_server_error
    AuthIssue.Cancelled -> R.string.auth_cancelled
    AuthIssue.GoogleUnavailable -> R.string.auth_google_unavailable
    AuthIssue.GoogleCleanupFailed -> R.string.auth_google_cleanup_failed
}

@Preview(showBackground = true)
@Composable
private fun AuthScreenPreview() {
    VoiceBridgeTheme {
        AuthScreen(AuthUiState(), {}, {}, {}, {})
    }
}
