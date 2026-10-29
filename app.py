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
    )
    if config:
        app.config.update(config)
    if app.config["SESSION_TTL_SECONDS"] <= 0:
        raise ValueError("SESSION_TTL_SECONDS must be greater than zero.")
    
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
