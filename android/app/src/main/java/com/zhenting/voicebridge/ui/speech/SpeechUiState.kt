package com.zhenting.voicebridge.ui.speech

import com.zhenting.voicebridge.data.model.SpeechIssue

typealias SpeechTarget = com.zhenting.voicebridge.data.local.DraftDestination
enum class SpeechPhase { Idle, Recording, Transcribing, Review, Retry }

data class SpeechUiState(
    val phase: SpeechPhase = SpeechPhase.Idle,
    val target: SpeechTarget? = null,
    val elapsedSeconds: Int = 0,
    val text: String = "",
    val error: SpeechIssue? = null
) {
    val isBusy: Boolean get() = phase != SpeechPhase.Idle && phase != SpeechPhase.Retry
}
