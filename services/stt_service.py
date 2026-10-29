"""Audio validation and OpenAI speech-to-text calls, independent of HTTP requests."""

import logging
from pathlib import Path
from time import perf_counter

from openai import APIError, OpenAI

from errors import ApiError, openai_error

logger = logging.getLogger(__name__)

MAX_AUDIO_BYTES = 25_000_000
AUDIO_TYPES = {
    ".mp3": "audio/mpeg",
    ".mp4": "audio/mp4",
    ".mpeg": "audio/mpeg",
    ".mpga": "audio/mpeg",
    ".m4a": "audio/mp4",
    ".wav": "audio/wav",
    ".webm": "audio/webm",
}


def transcribe_audio(
    filename: str, audio_bytes: bytes, *, client: OpenAI | None, model: str
) -> str:
    extension = Path(filename).suffix.lower()
    if extension not in AUDIO_TYPES:
        raise ApiError(
            415, "unsupported_audio_format", "Use an mp3, mp4, mpeg, mpga, m4a, wav, or webm file."
        )
    if not audio_bytes:
        raise ApiError(400, "empty_audio", "The audio file must not be empty.")
    if len(audio_bytes) > MAX_AUDIO_BYTES:
        raise ApiError(413, "audio_too_large", "The audio file must not exceed 25 MB.")
    if client is None:
        raise ApiError(
            503, "openai_not_configured", "Set OPENAI_API_KEY in .env and restart Flask."
        )

    started = perf_counter()
    logger.warning(
        "STT started: model=%s audio_bytes=%d",
        model,
        len(audio_bytes),
    )

    try:
        transcript = client.audio.transcriptions.create(
            model=model,
            file=(f"audio{extension}", audio_bytes, AUDIO_TYPES[extension]),
        )
    except APIError as error:
        logger.warning(
            "STT failed: elapsed=%.2fs error=%s cause=%s",
            perf_counter() - started,
            type(error).__name__,
            type(error.__cause__).__name__,
        )
        raise openai_error(error, task="transcription") from error

    logger.warning(
        "STT completed: elapsed=%.2fs",
        perf_counter() - started,
    )

    text = getattr(transcript, "text", None)
    if not isinstance(text, str):
        raise ApiError(502, "invalid_transcript", "The transcription service returned no text.")
    return text
