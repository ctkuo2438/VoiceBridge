package com.zhenting.voicebridge.data.model

const val MAX_AUDIO_BYTES = 25_000_000L
const val MAX_RECORDING_SECONDS = 120

enum class SpeechIssue {
    PermissionDenied, RecordingUnavailable, Interrupted, NoAudio, TooLarge, NoSpeech,
    Network, Timeout, SessionExpired, Unavailable
}

class SpeechException(val issue: SpeechIssue) : Exception(issue.name)
