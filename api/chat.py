"""HTTP routes for chat, translation requests, and conversation history."""

from flask import Blueprint, current_app, g, jsonify

from api.sessions import require_session
from errors import ApiError, json_body
from services import chat_service
from services.sheets_service import append_prompt

bp = Blueprint("chat", __name__)


@bp.post("/chat")
@require_session
def chat():
    data = json_body()
    message = data.get("message")
    if not isinstance(message, str) or not message.strip() or len(message.strip()) > 4000:
        raise ApiError(400, "invalid_message", "message must contain 1 to 4000 characters.")
    
    conversation_id = data.get("conversation_id")
    if conversation_id is not None and (
        not isinstance(conversation_id, str)
        or not conversation_id.strip()
        or len(conversation_id) > 128
    ):
        raise ApiError(400, "invalid_conversation", "conversation_id must be a non-empty string.")

    # write to SQLite db for memory use
    result = chat_service.send_message(
        message.strip(),
        conversation_id,
        g.chat_session,
        client=current_app.extensions["openai_client"],
        model=current_app.config["OPENAI_MODEL"],
        reasoning_effort=current_app.config["OPENAI_REASONING_EFFORT"],
        memo_ids=data.get("memo_ids"),
        memo_action=data.get("memo_action"),
    )
    try:
        # write to google sheet
        sheet_result = append_prompt(
            credentials_path=current_app.config["GOOGLE_APPLICATION_CREDENTIALS"],
            spreadsheet_id=current_app.config["SPREADSHEET_ID"],
            sheet_name=current_app.config["SHEET_NAME"],
            session_id=g.chat_session["id"], # session ID
            mode="chat",
            prompt=message.strip(),
        )
        sheet_status = {"saved": True, **sheet_result}

    except ApiError as error:
        current_app.logger.warning(
            "Chat completed; Sheet save failed: code=%s",
            error.code,
        )
        sheet_status = {
            "saved": False,
            "code": error.code,
            "error": error.message,
        }

    except Exception as error:
        current_app.logger.error(
            "Chat completed; unexpected Sheet error: type=%s",
            type(error).__name__,
        )
        sheet_status = {
            "saved": False,
            "code": "sheets_internal_error",
            "error": "Could not confirm Google Sheets save. Check the sheet.",
        }

    return jsonify(**result, sheet=sheet_status)


@bp.get("/conversations")
@require_session
def conversations():
    return jsonify(
        conversations=chat_service.list_conversations(g.chat_session["user_id"])
    )


@bp.get("/conversations/<conversation_id>/messages")
@require_session
def messages(conversation_id: str):
    return jsonify(
        conversation_id=conversation_id,
        messages=chat_service.list_messages(conversation_id, g.chat_session["user_id"]),
        memo_context=chat_service.memo_context(conversation_id, g.chat_session["user_id"]),
    )


@bp.delete("/conversations/<conversation_id>")
@require_session
def delete_conversation(conversation_id: str):
    chat_service.delete_conversation(conversation_id, g.chat_session["user_id"])
    return jsonify(message="Conversation deleted.")
