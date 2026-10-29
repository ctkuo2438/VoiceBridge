package com.zhenting.voicebridge.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhenting.voicebridge.data.repository.AuthException
import com.zhenting.voicebridge.data.repository.AuthIssue
import com.zhenting.voicebridge.data.repository.AuthRepository
import com.zhenting.voicebridge.data.repository.GoogleLoginChallenge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AuthViewModel(private val repository: AuthRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthUiState(user = repository.user.value))
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.user.collect { user ->
                _uiState.update { state ->
                    val expired = state.user != null && user == null &&
                        state.phase != AuthPhase.SigningOut
                    state.copy(
                        user = user,
                        error = if (expired) AuthIssue.SessionExpired else state.error
                    )
                }
            }
        }
    }

    // The screen owns this coroutine, so leaving it cancels the Google prompt flow.
    // No Activity, nonce, or Google ID token is kept in ViewModel fields.
    suspend fun signIn(getIdToken: suspend (GoogleLoginChallenge) -> String) {
        if (_uiState.value.isLoading || repository.user.value != null) return
        _uiState.update { it.copy(phase = AuthPhase.PreparingSignIn, error = null) }
        try {
            val challenge = repository.createGoogleChallenge()
            _uiState.update { it.copy(phase = AuthPhase.GoogleSignIn) }
            val idToken = getIdToken(challenge)
            _uiState.update { it.copy(phase = AuthPhase.Verifying) }
            repository.loginWithGoogle(challenge, idToken)
            _uiState.update { it.copy(user = repository.user.value) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AuthException) {
            _uiState.update {
                it.copy(error = error.issue.takeUnless { issue -> issue == AuthIssue.Cancelled })
            }
        } finally {
            _uiState.update { it.copy(phase = AuthPhase.Idle) }
        }
    }

    suspend fun signOut(clearGoogleSession: suspend () -> Unit) {
        if (_uiState.value.isLoading || repository.user.value == null) return
        _uiState.update { it.copy(phase = AuthPhase.SigningOut, error = null) }
        try {
            repository.logout()
            _uiState.update { it.copy(user = repository.user.value) }
            try {
                clearGoogleSession()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(error = AuthIssue.GoogleCleanupFailed) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AuthException) {
            _uiState.update { it.copy(error = error.issue) }
        } finally {
            _uiState.update { it.copy(phase = AuthPhase.Idle) }
        }
    }

    fun refreshUser() {
        if (_uiState.value.isLoading || repository.user.value == null) return
        _uiState.update { it.copy(phase = AuthPhase.Refreshing, error = null) }
        viewModelScope.launch {
            try {
                repository.refreshUser()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: AuthException) {
                _uiState.update { it.copy(error = error.issue) }
            } finally {
                _uiState.update { it.copy(phase = AuthPhase.Idle) }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    companion object {
        fun factory(repository: AuthRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { AuthViewModel(repository) }
        }
    }
}
