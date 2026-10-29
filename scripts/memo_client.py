"""Record speech and save the recognized text through Flask."""

import sys
from uuid import uuid4

import requests

from scripts.http_client import authenticate_client, request_json
from scripts.stt import record_microphone, transcribe_via_api

API_URL = "http://127.0.0.1:5003"


def main() -> int:
    try:
        with requests.Session() as client:
            user = authenticate_client(client, API_URL)
            print(f"Signed in as {user.get('name', 'Google user')}.")

            audio_upload = record_microphone()

            print("Sending audio to Flask...")
            text = transcribe_via_api(API_URL, audio_upload, client=client).strip()
            if not text:
                raise ValueError("No speech text was recognized.")

            print(f"Recognized: {text}")
            edited_text = input("Edit text, or press Enter to keep it: ").strip()
            prompt = edited_text or text

            if input("Save this memo? [y/N]: ").strip().lower() != "y":
                print("Memo was not submitted.")
                return 0

            result = request_json(
                client,
                "POST",
                API_URL + "/memos",
                json={"prompt": prompt, "request_id": str(uuid4())},
            )

            memo = result.get("memo")
            if result.get("saved") is not True or not isinstance(memo, dict) or not memo.get("id"):
                raise ValueError("Memo save was not confirmed. Refresh your memo history before retrying.")

            print(f"Saved memo: {memo['id']}")
            sheet = result.get("sheet", {})
            if sheet.get("saved") is True:
                print(f"Saved to Google Sheet: {sheet.get('updated_range', 'confirmed')}")
            else:
                print("Your memo is saved in the app. The Google Sheet copy was not confirmed.")
            return 0

    except (ValueError, OSError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1
    except (EOFError, KeyboardInterrupt):
        print("\nStopped.")
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
