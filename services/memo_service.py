"""Persist user-owned memos and separately record the Google Sheets copy status."""

import json
import logging

import db
from errors import ApiError
from services.sheets_service import append_prompt

logger = logging.getLogger(__name__)


def save_memo(
    prompt: str,
    chat_session,
    *,
    request_id: str | None = None,
    credentials_path: str,
    spreadsheet_id: str,
    sheet_name: str,
):
    row, created = db.create_memo(chat_session["user_id"], prompt, request_id)
    if created:
        try:
            result = append_prompt(
                credentials_path=credentials_path,
                spreadsheet_id=spreadsheet_id,
                sheet_name=sheet_name,
                session_id=chat_session["id"],
                mode="memo",
                prompt=prompt,
            )
            sheet = {"saved": True, **result}
        except ApiError as error:
            logger.warning("Memo saved; Sheet copy unconfirmed: code=%s", error.code)
            sheet = {"saved": False, "code": error.code, "error": error.message}
        except Exception as error:
            logger.error("Memo saved; unexpected Sheet error: type=%s", type(error).__name__)
            sheet = {
                "saved": False,
                "code": "sheets_internal_error",
                "error": "Could not confirm the Google Sheets copy. Your memo is saved in the app.",
            }
        row = db.save_memo_sheet_result(row["id"], chat_session["user_id"], sheet)
    else:
        # A retry must not append another Sheet row, including while the first request is running.
        sheet = json.loads(row["sheet_result"]) if row["sheet_result"] else {
            "saved": False,
            "code": "sheets_pending",
            "error": "The Google Sheets copy has not been confirmed yet.",
        }
    return {"saved": True, "memo": db.public_memo(row), "sheet": sheet}, created


def list_memos(user_id: str):
    return db.list_memos(user_id)
