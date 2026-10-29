"""Transcribe speech: uv run python -m scripts.stt --mic (or provide an audio file)."""

import argparse
import io
import os
import sys
import wave
from pathlib import Path
from urllib.parse import urlsplit

from dotenv import load_dotenv
from openai import OpenAI

PROJECT_DIR = Path(__file__).resolve().parents[1]
MAX_AUDIO_BYTES = 25_000_000


def record_microphone():
    """Capture microphone audio and return a WAV upload held only in memory."""
    import sounddevice as sd

    chunks = []
    total_bytes = 0
    input_problem = False
    too_long = False

    def callback(indata, _frames, _time, status):
        nonlocal total_bytes, input_problem, too_long
        if status:
            input_problem = True
        chunk = bytes(indata)
        # Leave room for the WAV header and stop capturing at the upload limit.
        if total_bytes + len(chunk) > MAX_AUDIO_BYTES - 44:
            too_long = True
            raise sd.CallbackStop
        chunks.append(chunk)
        total_bytes += len(chunk)

    input("Press Enter to start recording...")
    try:
        # Use the microphone's native sample rate to avoid unsupported-rate errors.
        sample_rate = int(sd.query_devices(kind="input")["default_samplerate"])
        with sd.RawInputStream(
            samplerate=sample_rate, channels=1, dtype="int16", callback=callback
        ):
            input("Recording. Speak now, then press Enter to stop.\n")
    except sd.PortAudioError as error:
        raise ValueError(
            "Cannot access the microphone. Check your input device and allow "
            "microphone access for your terminal or IDE."
        ) from error

    if too_long:
        raise ValueError("Recording exceeds 25 MB. Please make a shorter recording.")
    if input_problem:
        raise ValueError("Audio was lost during recording. Please record again.")
    if not total_bytes:
        raise ValueError("No audio was captured. Please record again.")

    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)  # int16: two bytes per sample.
        wav.setframerate(sample_rate)
        wav.writeframes(b"".join(chunks))
    return ("microphone.wav", buffer.getvalue(), "audio/wav")


def transcribe_via_api(api_url, audio_upload, *, client):
    """Send audio to Flask. The client does not send an OpenAI API key."""
    import requests

    try:
        with client.post(
            api_url.rstrip("/") + "/transcribe",
            files={"file": audio_upload},
            timeout=(10, 130),
        ) as response:
            try:
                payload = response.json()
            except ValueError as error:
                raise ValueError("Flask did not return a JSON response.") from error
            if not response.ok:
                message = payload.get("error") if isinstance(payload, dict) else None
                raise ValueError(message or f"Flask returned HTTP {response.status_code}.")
            if not isinstance(payload, dict) or not isinstance(payload.get("text"), str):
                raise ValueError("Flask returned an invalid transcription response.")
            return payload["text"]
    except requests.Timeout as error:
        raise ValueError("The Flask request timed out.") from error
    except requests.RequestException as error:
        raise ValueError("Cannot reach Flask. Check --api-url and start the server.") from error


def main():
    parser = argparse.ArgumentParser(
        description="Convert microphone speech or an audio file to text."
    )
    parser.add_argument("audio_file", type=Path, nargs="?", help="Optional path to an audio file")
    parser.add_argument(
        "--mic", action="store_true", help="Use the microphone (default without a file)"
    )
    parser.add_argument("--api-url", help="Send audio through Flask, e.g. http://127.0.0.1:5003")
    args = parser.parse_args()
    if args.audio_file and args.mic:
        parser.error("Use --mic or an audio file, not both.")
    if args.api_url:
        try:
            url = urlsplit(args.api_url)
            if url.scheme not in {"http", "https"} or not url.hostname or url.query or url.fragment:
                parser.error("--api-url must be an http:// or https:// Flask base URL.")
        except ValueError:
            parser.error("--api-url must be a valid Flask base URL.")

    # Read .env from the project root; existing shell variables take precedence.
    load_dotenv(PROJECT_DIR / ".env")
    api_key = os.getenv("OPENAI_API_KEY", "").strip()
    if not args.api_url and (not api_key or api_key == "replace_with_your_openai_api_key"):
        parser.error("Fill in OPENAI_API_KEY in VoiceBridge/.env first.")

    try:
        if args.audio_file:
            audio_path = args.audio_file.expanduser()
            if not audio_path.is_file():
                parser.error(f"Audio file not found: {audio_path}")
            if audio_path.suffix.lower() not in {
                ".mp3",
                ".mp4",
                ".mpeg",
                ".mpga",
                ".m4a",
                ".wav",
                ".webm",
            }:
                parser.error("Use an mp3, mp4, mpeg, mpga, m4a, wav, or webm file.")
            if not 0 < audio_path.stat().st_size <= MAX_AUDIO_BYTES:
                parser.error("The audio file must be nonempty and at most 25 MB.")
            audio_upload = (audio_path.name, audio_path.read_bytes())
        else:
            audio_upload = record_microphone()
    except (ValueError, OSError) as error:
        parser.error(str(error))
    except EOFError:
        parser.error(
            "Microphone mode needs an interactive terminal. Run this command in your terminal."
        )
    except KeyboardInterrupt:
        raise SystemExit("\nRecording cancelled.")

    if args.api_url:
        import requests

        from scripts.http_client import authenticate_client

        try:
            with requests.Session() as client:
                authenticate_client(client, args.api_url)
                print("Sending audio to Flask...", file=sys.stderr, flush=True)
                text = transcribe_via_api(
                    args.api_url,
                    audio_upload,
                    client=client,
                )
        except ValueError as error:
            parser.error(str(error))
        except (EOFError, KeyboardInterrupt):
            raise SystemExit("\nTranscription cancelled.")
    else:
        model = os.getenv("OPENAI_STT_MODEL", "gpt-transcribe").strip() or "gpt-transcribe"
        print("Transcribing...", file=sys.stderr, flush=True)
        with OpenAI(api_key=api_key, timeout=120.0, max_retries=0) as client:
            transcript = client.audio.transcriptions.create(model=model, file=audio_upload)
        text = transcript.text

    print(text)


if __name__ == "__main__":
    main()
