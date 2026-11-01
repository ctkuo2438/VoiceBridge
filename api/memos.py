"""HTTP endpoints for saving and listing the signed-in user's memos."""

from uuid import UUID

from flask import Blueprint, current_app, g, jsonify

from api.sessions import require_session
from errors import ApiError, json_body
from services import memo_service

bp = Blueprint("memos", __name__)
MAX_TITLE_CHARACTERS = 120


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
    title = data.get("title")
    if title is not None and (not isinstance(title, str) or len(title.strip()) > MAX_TITLE_CHARACTERS):
        raise ApiError(400, "invalid_memo_title", "Title must contain at most 120 characters.")
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
        title=title,
        credentials_path=current_app.config["GOOGLE_APPLICATION_CREDENTIALS"],
        spreadsheet_id=current_app.config["SPREADSHEET_ID"],
        sheet_name=current_app.config["SHEET_NAME"],
    )

    return jsonify(**result), 201 if created else 200


@bp.get("/memos")
@require_session
def list_memos():
    return jsonify(memos=memo_service.list_memos(g.chat_session["user_id"]))


@bp.put("/memos/<memo_id>")
@require_session
def update_memo(memo_id: str):
    data = json_body()
    title, prompt, version = data.get("title"), data.get("prompt"), data.get("version")
    if not isinstance(title, str) or not 1 <= len(title.strip()) <= MAX_TITLE_CHARACTERS:
        raise ApiError(400, "invalid_memo_title", "Title must contain 1 to 120 characters.")
    if not isinstance(prompt, str) or not prompt.strip():
        raise ApiError(400, "invalid_prompt", "Prompt must be a non-empty string.")
    if type(version) is not int or not 0 <= version < 2_147_483_647:
        raise ApiError(400, "invalid_memo_version", "Send the memo's nonnegative integer version.")
    return jsonify(**memo_service.update_memo(g.chat_session["user_id"], memo_id,
        title=title.strip(), prompt=prompt.strip(), version=version))
