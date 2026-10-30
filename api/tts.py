"""Authenticated text-to-speech requests returning WAV audio."""

from flask import Blueprint, Response, current_app, request
from werkzeug.exceptions import RequestEntityTooLarge

from api.sessions import require_session
from errors import ApiError, json_body
from services import tts_service

bp = Blueprint("tts", __name__)
MAX_REQUEST_BYTES = 64 * 1024


@bp.post("/tts")
@require_session
def create_speech():
    request.max_content_length = MAX_REQUEST_BYTES
    try:
        data = json_body()
    except RequestEntityTooLarge as error:
        raise ApiError(413, "tts_request_too_large", "The speech request must be at most 64 KB.") from error

    audio = tts_service.synthesize_speech(
        data.get("text"),
        api_key=current_app.config["GEMINI_API_KEY"],
        model=current_app.config["GEMINI_TTS_MODEL"],
        voice=current_app.config["GEMINI_TTS_VOICE"],
    )
    return Response(
        audio,
        mimetype="audio/wav",
        headers={
            "Content-Disposition": 'inline; filename="speech.wav"',
            "X-Content-Type-Options": "nosniff",
        },
    )
