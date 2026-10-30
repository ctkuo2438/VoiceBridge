"""Test Gemini speech generation: uv run python -m scripts.tts 'Hello, VoiceBridge!'"""

import argparse
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from dotenv import dotenv_values

from errors import ApiError
from services.tts_service import inspect_wav, synthesize_speech, validate_text

PROJECT_DIR = Path(__file__).resolve().parents[1]


def main() -> int:
    parser = argparse.ArgumentParser(description="Convert a short text sample to a WAV file with Gemini.")
    parser.add_argument("text", help="The exact text to read aloud (up to 4000 characters)")
    parser.add_argument("--output", type=Path, help="Output WAV path; defaults to instance/tts-<timestamp>.wav")
    parser.add_argument("--model", help="Override GEMINI_TTS_MODEL for this test only")
    parser.add_argument("--voice", help="Override GEMINI_TTS_VOICE for this test only")
    args = parser.parse_args()
    try:
        text = validate_text(args.text)
    except ApiError as error:
        parser.error(error.message)

    # Read only this project's .env. Shell settings take precedence.
    settings = {**dotenv_values(PROJECT_DIR / ".env"), **os.environ}
    api_key = (settings.get("GEMINI_API_KEY") or "").strip()
    model = (args.model or settings.get("GEMINI_TTS_MODEL") or "gemini-3.8-flash-tts").strip()
    voice = (args.voice or settings.get("GEMINI_TTS_VOICE") or "Kore").strip()
    if not api_key or api_key.startswith("replace_"):
        parser.error("Set GEMINI_API_KEY in VoiceBridge/.env first.")
    if not model or not voice:
        parser.error("The model and voice must be nonempty.")

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S-%f")
    output = (args.output or Path("instance") / f"tts-{timestamp}.wav").expanduser()
    if not output.is_absolute():
        output = PROJECT_DIR / output
    if output.exists():
        parser.error("The output file already exists. Choose a different --output path.")

    print(f"Generating speech: model={model} voice={voice}", flush=True)
    started = time.monotonic()
    try:
        audio = synthesize_speech(text, api_key=api_key, model=model, voice=voice)
        duration, sample_rate, channels = inspect_wav(audio)
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("xb") as destination:
            destination.write(audio)
    except ApiError as error:
        print(f"Error: {error.code}: {error.message}", file=sys.stderr)
        return 1
    except (ValueError, OSError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Speech generation cancelled.", file=sys.stderr)
        return 130

    print(f"Saved: {output}")
    print(f"Audio: {duration:.2f}s, {sample_rate} Hz, {channels} channel(s), {len(audio)} bytes")
    print(f"Completed in {time.monotonic() - started:.2f}s.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
