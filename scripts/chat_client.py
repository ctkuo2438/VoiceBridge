"""Interactive Python client for Flask chat: uv run python -m scripts.chat_client."""

import argparse
import sys
from urllib.parse import urlsplit
from scripts.stt import record_microphone, transcribe_via_api
from scripts.http_client import authenticate_client, request_json

import requests


def request_json(client, method, url, **kwargs):
    try:
        with client.request(method, url, timeout=(10, 180), **kwargs) as response:
            try:
                payload = response.json()
            except ValueError as error:
                raise ValueError("Flask did not return a JSON response.") from error
            if not response.ok:
                message = payload.get("error") if isinstance(payload, dict) else None
                raise ValueError(message or f"Flask returned HTTP {response.status_code}.")
            if not isinstance(payload, dict):
                raise ValueError("Flask returned an invalid response.")
            return payload
    except requests.Timeout as error:
        raise ValueError("The Flask request timed out.") from error
    except requests.RequestException as error:
        raise ValueError("Cannot reach Flask. Start it with: uv run python app.py") from error


def main():
    parser = argparse.ArgumentParser(description="Chat or request translations through Flask.")
    parser.add_argument("--api-url", default="http://127.0.0.1:5003", help="Flask base URL")
    args = parser.parse_args()
    try:
        url = urlsplit(args.api_url)
        if url.scheme not in {"http", "https"} or not url.hostname or url.query or url.fragment:
            parser.error("--api-url must be an http:// or https:// Flask base URL.")
    except ValueError:
        parser.error("--api-url must be a valid Flask base URL.")
    base_url = args.api_url.rstrip("/")

    with requests.Session() as client:
        try:
            user = authenticate_client(client, base_url)
        except ValueError as error:
            parser.error(str(error))
        except (EOFError, KeyboardInterrupt):
            print("\nSign-in cancelled.")
            return

        conversation_id = None
        print(
            f"Signed in as {user.get('name', 'Google user')}. "
            "Use /mic, /new, or /quit."
        )

        while True:
            try:
                message = input("You: ").strip()

            except (EOFError, KeyboardInterrupt):
                print("\nGoodbye.")
                break

            if not message:
                continue

            if message == "/quit":
                break

            if message == "/new":
                conversation_id = None
                print("The next message will start a new conversation.")
                continue
            
            if message == "/mic":
                try:
                    audio_upload = record_microphone()

                    print("Sending audio to Flask...")
                    text = transcribe_via_api(base_url, audio_upload, client=client).strip()
                    if not text:
                        raise ValueError("No speech text was recognized.")

                    print(f"Recognized: {text}")
                    edited_text = input(
                        "Edit text, or press Enter to keep it: "
                    ).strip()
                    message = edited_text or text

                    if input("Send this message? [y/N]: ").strip().lower() != "y":
                        print("Message was not submitted.")
                        continue

                except (ValueError, OSError) as error:
                    print(f"Speech input error: {error}", file=sys.stderr)
                    continue
                except (EOFError, KeyboardInterrupt):
                    print("\nSpeech input stopped.")
                    continue

            payload = {"message": message}

            if conversation_id:
                payload["conversation_id"] = conversation_id

            try:
                result = request_json(client, "POST", base_url + "/chat", json=payload)
                reply = result.get("reply")
                result_id = result.get("conversation_id")
                if not isinstance(reply, str) or not isinstance(result_id, str):
                    raise ValueError("Flask returned an invalid chat response.")
                
                conversation_id = result_id
                print(f"Assistant: {reply}")
                
                # Sheet warnings will not make chat replies disappear
                sheet_status = result.get("sheet")

                if isinstance(sheet_status, dict) and sheet_status.get("saved") is True:
                    print(f"Saved to Google Sheets: {sheet_status.get('updated_range')}")
                elif isinstance(sheet_status, dict):
                    print(
                        f"Sheet warning: {sheet_status.get('error', 'Save not confirmed.')}",
                        file=sys.stderr,
                    )
                else:
                    print(
                        "Sheet save status unavailable. Check the sheet.",
                        file=sys.stderr,
                    )

            except ValueError as error:
                print(f"Error: {error}", file=sys.stderr)


if __name__ == "__main__":
    main()
