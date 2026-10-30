package com.zhenting.voicebridge.data.model

const val MAX_TTS_CHARACTERS = 4000
const val MAX_TTS_AUDIO_BYTES = 25_000_000

enum class TtsIssue {
    InvalidText, Network, Timeout, SessionExpired, Unavailable,
    RateLimited, InvalidAudio, PlaybackUnavailable, Interrupted
}

class TtsException(val issue: TtsIssue) : Exception(issue.name)

/** Split at word boundaries when possible, without cutting a Unicode code point. */
fun speechChunks(text: String): List<String> {
    val source = text.trim()
    if (source.isEmpty()) return emptyList()
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < source.length) {
        val count = source.codePointCount(start, source.length)
        var end = source.offsetByCodePoints(start, minOf(count, MAX_TTS_CHARACTERS))
        if (end < source.length) {
            val boundary = (end - 1 downTo start + (end - start) / 2)
                .firstOrNull { source[it].isWhitespace() }
            if (boundary != null) end = boundary
        }
        source.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(chunks::add)
        start = end
        while (start < source.length && source[start].isWhitespace()) start++
    }
    return chunks
}
