"""Generate validated WAV audio with Gemini for both Flask and the test CLI."""

import base64
import binascii
import io
import json
import logging
import time
import wave

import requests

from errors import ApiError

logger = logging.getLogger(__name__)

GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/interactions"
MAX_TEXT_CHARACTERS = 4000
MAX_AUDIO_BYTES = 25_000_000
MAX_RESPONSE_BYTES = ((MAX_AUDIO_BYTES + 2) // 3) * 4 + 1024 * 1024


def validate_text(text: object) -> str:
    if not isinstance(text, str) or not text.strip():
        raise ApiError(400, "invalid_text", "Text must be a non-empty string.")
    text = text.strip()
    if len(text) > MAX_TEXT_CHARACTERS:
        raise ApiError(
            400,
            "text_too_long",
            f"Text must contain at most {MAX_TEXT_CHARACTERS} characters.",
        )
    return text


def inspect_wav(audio: bytes) -> tuple[float, int, int]:
    """Reject an empty or incomplete PCM WAV before it reaches the player."""
    try:
        with wave.open(io.BytesIO(audio), "rb") as recording:
            frames = recording.getnframes()
            sample_rate = recording.getframerate()
            channels = recording.getnchannels()
            data = recording.readframes(frames)
            expected_bytes = frames * channels * recording.getsampwidth()
            if not frames or not sample_rate or not channels or len(data) != expected_bytes:
                raise ValueError("The WAV recording is empty or incomplete.")
            return frames / sample_rate, sample_rate, channels
    except (wave.Error, EOFError, OverflowError) as error:
        raise ValueError("The audio is not a supported PCM WAV recording.") from error


def _gemini_error(status: int) -> ApiError:
    # Never include Google's response body, the request text, or credentials in errors.
    logger.warning("Gemini TTS failed: http_status=%s", status)
    if status in {408, 504}:
        return ApiError(504, "gemini_timeout", "The speech request timed out. Try again later.")
    if status == 429:
        return ApiError(429, "gemini_rate_limit", "Speech generation limit reached. Try again later.")
    if status == 401:
        return ApiError(503, "gemini_auth_failed", "The speech service could not authenticate this request.")
    if status == 403:
        return ApiError(503, "gemini_permission_denied", "The speech service denied this request.")
    if status == 404:
        return ApiError(503, "gemini_model_unavailable", "The configured speech model is unavailable.")
    if status == 400:
        return ApiError(400, "tts_request_rejected", "Unable to generate speech for this text.")
    return ApiError(502, "gemini_failed", "Unable to generate speech right now.")


def _decode_audio(payload: object) -> bytes:
    if not isinstance(payload, dict) or payload.get("status") != "completed":
        raise ValueError("The Gemini interaction did not complete.")
    steps = payload.get("steps")
    if not isinstance(steps, list):
        raise ValueError("The Gemini response has no output steps.")

    audio_item = None
    for step in steps:
        if not isinstance(step, dict) or step.get("type") != "model_output":
            continue
        content = step.get("content")
        if not isinstance(content, list):
            raise ValueError("The Gemini output content is invalid.")
        for item in content:
            if isinstance(item, dict) and item.get("type") == "audio":
                audio_item = item
    if audio_item is None or not isinstance(audio_item.get("data"), str):
        raise ValueError("The Gemini response contains no inline audio.")

    audio = base64.b64decode(audio_item["data"], validate=True)
    if not audio or len(audio) > MAX_AUDIO_BYTES:
        raise ValueError("The generated audio is empty or too large.")
    # Gemini 3.8 supplies a WAV header already; do not wrap it a second time.
    inspect_wav(audio)
    return audio


def synthesize_speech(text: object, *, api_key: str, model: str, voice: str) -> bytes:
    text = validate_text(text)
    if (
        not isinstance(api_key, str)
        or not api_key.strip()
        or api_key.strip().startswith("replace_")
        or not isinstance(model, str)
        or not model.strip()
        or not isinstance(voice, str)
        or not voice.strip()
    ):
        raise ApiError(503, "gemini_not_configured", "Speech generation is not configured on the server.")

    body = {
        "model": model.strip(),
        "input": [{"type": "user_input", "content": [{"type": "text", "text": text}]}],
        "response_format": {"type": "audio"},
        "generation_config": {"speech_config": [{"voice": voice.strip()}]},
        "store": False,
    }
    started = time.monotonic()
    try:
        # Header authentication, no redirects, and no automatic billable retries.
        with requests.post(
            GEMINI_URL,
            headers={"x-goog-api-key": api_key.strip()},
            json=body,
            timeout=(10, 120),
            allow_redirects=False,
            stream=True,
        ) as response:
            if not 200 <= response.status_code < 300:
                raise _gemini_error(response.status_code)
            chunks = []
            total_bytes = 0
            for chunk in response.iter_content(chunk_size=64 * 1024):
                total_bytes += len(chunk)
                if total_bytes > MAX_RESPONSE_BYTES:
                    raise ApiError(502, "invalid_speech_response", "The speech service returned too much data.")
                chunks.append(chunk)
    except requests.Timeout as error:
        logger.warning("Gemini TTS failed: type=%s", type(error).__name__)
        raise ApiError(504, "gemini_timeout", "The speech request timed out. Try again later.") from error
    except requests.RequestException as error:
        logger.warning("Gemini TTS failed: type=%s", type(error).__name__)
        raise ApiError(502, "gemini_unreachable", "Unable to reach the speech service.") from error

    try:
        payload = json.loads(b"".join(chunks))
        audio = _decode_audio(payload)
    except (ValueError, binascii.Error) as error:
        raise ApiError(502, "invalid_speech_response", "The speech service did not return valid WAV audio.") from error
    logger.info("TTS completed: elapsed=%.2fs audio_bytes=%s", time.monotonic() - started, len(audio))
    return audio
