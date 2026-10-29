"""HTTP endpoints for saving and listing the signed-in user's memos."""

from uuid import UUID

from flask import Blueprint, current_app, g, jsonify

from api.sessions import require_session
from errors import ApiError, json_body
from services import memo_service

bp = Blueprint("memos", __name__)


@bp.post("/memos")
@require_session
def create_memo():
    data = json_body()
    prompt = data.get("prompt")

    if not isinstance(prompt, str) or not prompt.strip():
        raise ApiError(
            400,
            "invalid_prompt",
            "Prompt must be a non-empty string.",
        )

    request_id = data.get("request_id")
    if request_id is not None:
        try:
            if not isinstance(request_id, str):
                raise ValueError
            request_id = str(UUID(request_id))
        except ValueError as error:
            raise ApiError(400, "invalid_request_id", "request_id must be a UUID.") from error

    result, created = memo_service.save_memo(
        prompt.strip(),
        g.chat_session,
        request_id=request_id,
        credentials_path=current_app.config["GOOGLE_APPLICATION_CREDENTIALS"],
        spreadsheet_id=current_app.config["SPREADSHEET_ID"],
        sheet_name=current_app.config["SHEET_NAME"],
    )

    return jsonify(**result), 201 if created else 200


@bp.get("/memos")
@require_session
def list_memos():
    return jsonify(memos=memo_service.list_memos(g.chat_session["user_id"]))
