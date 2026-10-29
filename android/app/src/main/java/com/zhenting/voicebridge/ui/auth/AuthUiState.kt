package com.zhenting.voicebridge.ui.auth

import com.zhenting.voicebridge.data.model.UserProfile
import com.zhenting.voicebridge.data.repository.AuthIssue

enum class AuthPhase { Idle, PreparingSignIn, GoogleSignIn, Verifying, Refreshing, SigningOut }

data class AuthUiState(
    val user: UserProfile? = null,
    val phase: AuthPhase = AuthPhase.Idle,
    val error: AuthIssue? = null
) {
    val isLoading: Boolean get() = phase != AuthPhase.Idle
}
