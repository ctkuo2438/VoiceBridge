"""Configure Flask and register API modules. Start with: uv run python app.py."""

import os
from pathlib import Path

from dotenv import dotenv_values
from flask import Flask, jsonify
from flask.json.provider import DefaultJSONProvider
from openai import OpenAI

import db
from api.chat import bp as chat_bp
from api.sessions import bp as sessions_bp
from api.transcribe import bp as transcribe_bp
from api.tts import bp as tts_bp
from api.memos import bp as memos_bp
from api.auth import bp as auth_bp
from errors import register_error_handlers
from services.stt_service import MAX_AUDIO_BYTES

PROJECT_DIR = Path(__file__).resolve().parent


def create_app(config=None, *, openai_client=None):
    app = Flask(__name__)

    # Load this project's .env only. Shell variables take precedence.
    settings = {**dotenv_values(PROJECT_DIR / ".env"), **os.environ}

    database = Path(settings.get("DATABASE_PATH") or "instance/voicebridge.sqlite3").expanduser()
    if not database.is_absolute():
        database = PROJECT_DIR / database

    app.config.from_mapping(
        OPENAI_API_KEY=(settings.get("OPENAI_API_KEY") or "").strip(),
        OPENAI_STT_MODEL=(settings.get("OPENAI_STT_MODEL") or "").strip() or "gpt-transcribe",
        OPENAI_MODEL=(settings.get("OPENAI_MODEL") or "").strip() or "gpt-5.6-sol",
        OPENAI_REASONING_EFFORT=(settings.get("OPENAI_REASONING_EFFORT") or "").strip() or "medium",
        SESSION_TTL_SECONDS=int(settings.get("SESSION_TTL_SECONDS") or "86400"),
        JWT_SECRET_KEY=(settings.get("JWT_SECRET_KEY") or "").strip(),
        JWT_ISSUER=(settings.get("JWT_ISSUER") or "").strip() or "voicebridge",
        JWT_AUDIENCE=(settings.get("JWT_AUDIENCE") or "").strip() or "voicebridge-app",
        DATABASE=str(database),
        HOST=settings.get("HOST") or "127.0.0.1",
        PORT=int(settings.get("PORT") or "5003"),
        # The file limit is 25 MB; allow additional space for multipart headers.
        MAX_CONTENT_LENGTH=MAX_AUDIO_BYTES + 1024 * 1024,
        # Google Sheet
        GOOGLE_APPLICATION_CREDENTIALS=(settings.get("GOOGLE_APPLICATION_CREDENTIALS") or "").strip(),
        SPREADSHEET_ID=(settings.get("SPREADSHEET_ID") or "").strip(),
        SHEET_NAME=(settings.get("SHEET_NAME") or "").strip() or "Logs",
        # Google SSO
        GOOGLE_CLIENT_ID=(settings.get("GOOGLE_CLIENT_ID") or "").strip(),
        # Gemini text-to-speech
        GEMINI_API_KEY=(settings.get("GEMINI_API_KEY") or "").strip(),
        GEMINI_TTS_MODEL=(settings.get("GEMINI_TTS_MODEL") or "").strip() or "gemini-3.8-flash-tts",
        GEMINI_TTS_VOICE=(settings.get("GEMINI_TTS_VOICE") or "").strip() or "Kore",
    )
    if config:
        app.config.update(config)
    if app.config["SESSION_TTL_SECONDS"] <= 0:
        raise ValueError("SESSION_TTL_SECONDS must be greater than zero.")

    jwt_secret = app.config["JWT_SECRET_KEY"]
    if (
        not isinstance(jwt_secret, str)
        or len(jwt_secret.encode("utf-8")) < 32
        or jwt_secret.startswith("replace_")
    ):
        raise ValueError(
            "Set JWT_SECRET_KEY in .env to a random secret of at least 32 bytes, "
            "then restart Flask."
        )
    for name in ("JWT_ISSUER", "JWT_AUDIENCE"):
        if not isinstance(app.config[name], str) or not app.config[name].strip():
            raise ValueError(f"{name} must be a nonempty string.")
    
    json_provider = DefaultJSONProvider(app)
    json_provider.ensure_ascii = False
    app.json = json_provider

    api_key = app.config["OPENAI_API_KEY"]
    client = openai_client
    if client is None and api_key and api_key != "replace_with_your_openai_api_key":
        client = OpenAI(api_key=api_key, timeout=120.0, max_retries=0)
    app.extensions["openai_client"] = client
    db.init_db(app)
    register_error_handlers(app)
    app.register_blueprint(sessions_bp)
    app.register_blueprint(chat_bp)
    app.register_blueprint(transcribe_bp)
    app.register_blueprint(tts_bp)
    app.register_blueprint(memos_bp)
    app.register_blueprint(auth_bp)

    @app.get("/health")
    def health():
        # Liveness only; this does not call OpenAI or validate the API key.
        return jsonify(status="ok")

    @app.after_request
    def response_headers(response):
        response.headers["Cache-Control"] = "no-store"
        return response

    return app


if __name__ == "__main__":
    app = create_app()
    app.run(host=app.config["HOST"], port=app.config["PORT"], debug=False)
