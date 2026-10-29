import os
import sys
from pathlib import Path

from dotenv import load_dotenv

from errors import ApiError
from services.sheets_service import append_prompt

PROJECT_DIR = Path(__file__).resolve().parents[1]


def main() -> int:
    load_dotenv(PROJECT_DIR / ".env")

    try:
        result = append_prompt(
            credentials_path=os.getenv("GOOGLE_APPLICATION_CREDENTIALS", "").strip(),
            spreadsheet_id=os.getenv("SPREADSHEET_ID", "").strip(),
            sheet_name=os.getenv("SHEET_NAME", "Logs").strip(),
            session_id="manual-test",
            mode="memo",
            prompt="This is a test memo from Python.",
        )
    except ApiError as error:
        print(f"Failed: {error.code}: {error.message}", file=sys.stderr)
        return 1

    print("Saved to Google Sheets.")
    print(f"Range: {result['updated_range']}")
    print(f"Rows added: {result['updated_rows']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())