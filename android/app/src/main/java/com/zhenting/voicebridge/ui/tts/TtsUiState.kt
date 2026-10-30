package com.zhenting.voicebridge.ui.tts

import com.zhenting.voicebridge.data.model.TtsIssue

enum class TtsPhase { Idle, Generating, Preparing, Playing }

data class TtsUiState(
    val itemId: String? = null,
    val phase: TtsPhase = TtsPhase.Idle,
    val part: Int = 0,
    val totalParts: Int = 0,
    val error: TtsIssue? = null
) {
    val isActive: Boolean get() = phase != TtsPhase.Idle
}
