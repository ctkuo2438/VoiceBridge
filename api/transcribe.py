"""HTTP audio uploads; speech recognition is implemented in the STT service."""

from flask import Blueprint, current_app, jsonify, request

from errors import ApiError
from services import stt_service
from api.sessions import require_session

bp = Blueprint("transcription", __name__)


@bp.post("/transcribe")
@require_session
def transcribe():
    if request.mimetype != "multipart/form-data":
        raise ApiError(
            415, "multipart_required", "Send multipart/form-data with an audio file named 'file'."
        )
    files = request.files.getlist("file")
    filename = files[0].filename if len(files) == 1 else None
    if not filename:
        raise ApiError(400, "file_required", "Send exactly one audio file in the 'file' field.")

    audio_bytes = files[0].read(stt_service.MAX_AUDIO_BYTES + 1)
    text = stt_service.transcribe_audio(
        filename,
        audio_bytes,
        client=current_app.extensions["openai_client"],
        model=current_app.config["OPENAI_STT_MODEL"],
    )
    return jsonify(text=text)
