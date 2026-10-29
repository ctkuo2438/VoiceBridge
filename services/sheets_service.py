"""Google Sheets authorization and prompt storage."""

import requests
from pathlib import Path

from google.auth.transport.requests import AuthorizedSession
from google.oauth2.service_account import Credentials
from google.auth.exceptions import RefreshError, TransportError

from errors import ApiError
from datetime import UTC, datetime
from urllib.parse import quote


PROJECT_DIR = Path(__file__).resolve().parents[1]
SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets"
SHEETS_API_ROOT = "https://sheets.googleapis.com/v4/spreadsheets"
SHEET_HEADERS = ["Timestamp", "SessionID", "Mode", "Prompt"]


# credentials_path is GOOGLE_APPLICATION_CREDENTIALS in .env
def create_sheets_session(credentials_path: str) -> AuthorizedSession:
    if not credentials_path.strip():
        raise ApiError(
            503,
            "sheets_not_configured",
            "Set GOOGLE_APPLICATION_CREDENTIALS in .env.",
        )

    path = Path(credentials_path).expanduser()
    if not path.is_absolute():
        path = PROJECT_DIR / path

    try:
        # read service account JSON file
        credentials = Credentials.from_service_account_file(
            str(path),
            scopes=[SHEETS_SCOPE],
        )
    except (OSError, ValueError, KeyError) as error:
        raise ApiError(
            503,
            "sheets_credentials_invalid",
            "Could not load Google service account credentials.",
        ) from error

    return AuthorizedSession(
        credentials,
        refresh_timeout=15,
        max_refresh_attempts=0,
    )


# chat and memo mode shared the same append_prompt(),
#   Memo API and Chat API will call this function for autosave.
def append_prompt(
    *,
    credentials_path: str,
    spreadsheet_id: str,
    sheet_name: str,
    session_id: str,
    mode: str,
    prompt: str,
) -> dict:
    if not spreadsheet_id.strip() or not sheet_name.strip():
        raise ApiError(
            503,
            "sheets_not_configured",
            "Set SPREADSHEET_ID and SHEET_NAME in .env.",
        )

    if mode not in {"memo", "chat"}:
        raise ApiError(400, "invalid_mode", "Mode must be memo or chat.")

    if not session_id.strip() or not prompt.strip():
        raise ApiError(
            400,
            "invalid_record",
            "Session ID and prompt must not be empty.",
        )

    timestamp = datetime.now(UTC).isoformat(timespec="seconds")
    row = [timestamp, session_id, mode, prompt]

    base_url = f"{SHEETS_API_ROOT}/{quote(spreadsheet_id, safe='')}"
    quoted_sheet = "'" + sheet_name.replace("'", "''") + "'"
    header_range = quote(f"{quoted_sheet}!A1:D1", safe="")
    table_range = quote(f"{quoted_sheet}!A:D", safe="")

    try:
        with create_sheets_session(credentials_path) as session:
            header_response = session.get( # get the first row
                f"{base_url}/values/{header_range}",
                timeout=15,
            )
            header_response.raise_for_status()
            header_data = header_response.json()

            if (
                not isinstance(header_data, dict)
                or header_data.get("values") != [SHEET_HEADERS]
            ):
                raise ApiError(
                    409,
                    "sheets_headers_invalid",
                    "Row 1 must contain Timestamp, SessionID, Mode, Prompt.",
                )

            response = session.post( # add new row data
                f"{base_url}/values/{table_range}:append",
                params={
                    "valueInputOption": "RAW",
                    "insertDataOption": "INSERT_ROWS",
                },
                json={"majorDimension": "ROWS", "values": [row]},
                timeout=15,
            )
            response.raise_for_status()
            result = response.json()

    except requests.Timeout as error:
        raise ApiError(
            504,
            "sheets_timeout",
            "Google Sheets timed out. Check the sheet before trying again.",
        ) from error
    except RefreshError as error:
        raise ApiError(
            503,
            "sheets_auth_failed",
            "Google rejected the service account credentials.",
        ) from error
    except ValueError as error:
        raise ApiError(
            502,
            "sheets_invalid_response",
            "Google returned an invalid response. Check the sheet.",
        ) from error
    except (requests.RequestException, TransportError) as error:
        raise ApiError(
            502,
            "sheets_request_failed",
            "Check Google Sheets access, spreadsheet ID, and sheet name.",
        ) from error

    updates = result.get("updates", {}) if isinstance(result, dict) else {}
    if not isinstance(updates, dict):
        updates = {}

    updated_range = updates.get("updatedRange")
    if (
        updates.get("updatedRows") != 1
        or not isinstance(updated_range, str)
        or not updated_range
    ):
        raise ApiError(
            502,
            "sheets_write_unconfirmed",
            "Google did not confirm the write. Check the sheet.",
        )

    return {
        "timestamp": timestamp,
        "updated_range": updated_range,
        "updated_rows": 1,
    }