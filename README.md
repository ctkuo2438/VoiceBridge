# VoiceBridge

An Android application that turns speech into notes and conversations.

VoiceBridge supports Google sign-in, speech-to-text, chat and translation, persistent Memo history, and conversations based on selected Memos.

## Features

- **Google sign-in:** Authenticate with Google and receive a separate Flask-signed app access JWT.
- **Speech-to-text:** Record audio, transcribe it through Flask, and review or edit the recognized text.
- **Chat and translation:** Send typed or transcribed messages and continue saved conversations.
- **Voice Memos:** Save reviewed text and browse personal Memo history.
- **Memos to Chat:** Select one or multiple saved Memos as reference material for a new conversation.
- **Google Sheets logging:** Record submitted user prompts with their timestamp, session ID, and mode.
- **Read aloud:** Play saved Memos and AI replies using Gemini TTS through Flask, with generation status and stop controls.

## Documentation

- [Application architecture and workflows](docs/voicebridge-app-workflows-en.html)
- [Backend architecture flowchart](docs/voicebridge-flowcharts-en.html)
- [Interactive workflow walkthrough](docs/voicebridge-interactive-journey-en.html)

Download or open the HTML files locally in a browser to view the diagrams and interactions.

## Tech Stack

| Layer | Technologies |
| --- | --- |
| Android UI | Kotlin, Jetpack Compose |
| Presentation | MVVM, ViewModel, StateFlow, Coroutines |
| Networking | Retrofit, OkHttp |
| Authentication | Android Credential Manager, Google ID tokens, Flask-signed JWTs |
| Backend | Python, Flask |
| AI | OpenAI Audio Transcriptions API and Responses API; Gemini TTS |
| Persistence | SQLite |
| Prompt logging | Google Sheets API |
| Python environment | uv |

## Architecture

```text
Jetpack Compose
      ↓ user actions
ViewModel
      ↓
Repository
      ↓
Retrofit → OkHttp + Flask Bearer token
      ↓
Flask API → Services
                ├── OpenAI: transcription, chat, and translation
                ├── Gemini: text-to-speech audio
                ├── db.py → SQLite: users, sessions, chats, and Memos
                └── Google Sheets: user prompt log
```

Responses return through the networking and repository layers.
ViewModels publish updated UiState through StateFlow, and Compose updates the screen.

Google proves the user's identity. Flask verifies the Google ID token
and one-time nonce, then creates an application session and signs an access JWT.
Protected requests verify the JWT signature and claims, then check the SQLite
session so signing out immediately revokes access.

## User Workflows

### Voice Memo

Record → transcribe → review or edit → confirm save.

Flask saves the Memo in SQLite, then attempts to copy its text to Google Sheets.
The saved note appears in the user's Memo history, newest first.

### Voice or Text Chat

Type or record → review text → Send → receive an answer or translation.

SQLite stores the user message and AI reply. Follow-up messages use the
same `conversation_id` to retain conversation context.

### Memos to Chat

Select saved Memos → start a new Chat → add a question → Send.

Flask verifies that the selected Memos belong to the current user.
It saves Memo snapshots with the conversation, so reopening the Chat
restores the reference material used at the start.

Google Sheets records the new question, without duplicating the attached notes.

### Read Aloud

Tap **Read aloud** on a saved Memo or an AI reply. Android's `TtsViewModel`
asks `TtsRepository` for WAV audio through the authenticated `POST /tts` route,
then `AudioPlayer` plays it using the device's media volume and current audio
output. Longer content is split into requests of up to 4000 Unicode characters
and played in order, with the current part shown in the UI.

Tap **Stop** during playback or **Cancel speech** during generation. Selecting
another item replaces the current playback. Mode changes, changing chats,
starting a recording, leaving the foreground, and signing out stop speech.
Generated audio stays in memory and is released when playback ends. Android
requests audio focus and stops when audio focus is lost or headphones disconnect.

## Project Structure

```text
VoiceBridge/
├── android/          # Android application
├── api/              # Flask routes and request validation
├── services/         # Application logic and external API calls
├── scripts/          # Development and command-line clients
├── templates/        # Local Google sign-in test page
├── tests/            # Backend automated tests
├── docs/             # HTML architecture and workflow guides
├── app.py            # Flask application setup
├── db.py             # SQLite queries and transactions
├── .env_example      # Configuration template
├── pyproject.toml    # Python dependencies
└── uv.lock           # Locked Python dependencies
```

## Getting Started

### Requirements

- Python 3.13 or later
- uv
- Android Studio and Android SDK 35
- A Google Play-enabled Android emulator or a compatible Android device
- An OpenAI API key
- A Google Cloud project with OAuth clients and Google Sheets API access

### Backend Setup

From the repository root:

```bash
uv sync
cp .env_example .env
```

Update `.env` with your configuration:

```dotenv
OPENAI_API_KEY=your_openai_api_key
OPENAI_STT_MODEL=your_transcription_model
OPENAI_MODEL=your_responses_model
OPENAI_REASONING_EFFORT=medium

GEMINI_API_KEY=your_gemini_api_key
GEMINI_TTS_MODEL=gemini-3.8-flash-tts
GEMINI_TTS_VOICE=Kore

GOOGLE_CLIENT_ID=your_web_oauth_client_id.apps.googleusercontent.com
GOOGLE_APPLICATION_CREDENTIALS=/absolute/path/to/service-account.json
SPREADSHEET_ID=your_spreadsheet_id
SHEET_NAME=Logs

DATABASE_PATH=instance/voicebridge.sqlite3
SESSION_TTL_SECONDS=86400
JWT_SECRET_KEY=replace_with_a_random_secret_of_at_least_32_bytes
JWT_ISSUER=voicebridge
JWT_AUDIENCE=voicebridge-app
HOST=127.0.0.1
PORT=5003
```

Replace the placeholders before starting. Choose OpenAI models available
to your project and compatible with the backend's configured options.

Generate a backend-only signing secret and paste its output into `JWT_SECRET_KEY`:

```bash
uv run python -c "import secrets; print(secrets.token_urlsafe(48))"
```

Flask requires a signing secret of at least 32 bytes. Keep it in `.env`;
Android never receives the signing secret. Changing it invalidates existing app JWTs.

For Google integration:

1. Enable the Google Sheets API.
2. Create a service account and obtain its JSON credentials.
3. Share the spreadsheet with the service account as an Editor.
4. Create a Web OAuth client and use its ID as `GOOGLE_CLIENT_ID`.
5. Create an Android OAuth client for `com.zhenting.voicebridge` using
   the signing certificate's SHA-1 fingerprint.

Create a worksheet named `Logs` with these headers:

```text
Timestamp | SessionID | Mode | Prompt
```

Start Flask:

```bash
uv run python app.py
```

SQLite tables are initialized automatically.

Check that the server is responding:

```bash
curl http://127.0.0.1:5003/health
```

### Android Setup

1. Open the `android` directory in Android Studio.
2. Sync Gradle using Android Studio's bundled JDK.
3. Obtain the debug signing fingerprint if needed:

   ```bash
   cd android
   ./gradlew signingReport
   ```

4. Register the fingerprint with the Android OAuth client.
5. Start a Google Play-enabled emulator and sign in to a Google account.
6. Run VoiceBridge and grant microphone permission when requested.

The default emulator backend URL is:

```text
http://10.0.2.2:5003/
```

The backend URL can be configured through the
`voicebridgeApiBaseUrl` Gradle property.

## Main API Endpoints

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | /health | Server liveness |
| POST | /auth/google/challenge | Create a login challenge |
| POST | /auth/google | Verify Google identity and issue a Flask-signed app JWT |
| GET | /auth/me | Get the authenticated user |
| POST | /auth/logout | Revoke the current session |
| POST | /transcribe | Convert an uploaded audio file to text |
| POST | /tts | Convert text to WAV audio with Gemini |
| POST | /chat | Generate a reply or translation |
| GET | /conversations | List the user's conversations |
| GET | /conversations/{id}/messages | Load messages and attached Memo context |
| POST | /memos | Save a Memo |
| GET | /memos | List the user's Memos |

Protected endpoints require:

```http
Authorization: Bearer <Flask access JWT>
```

### Text-to-speech request

`POST /tts` accepts a JSON object with a nonempty `text` string, up to 4000
characters after trimming. The JSON request body must be at most 64 KB.
The model and voice come from the server's Gemini settings.

```json
{"text":"Hello! This is VoiceBridge."}
```

A successful request returns `200 OK` with `Content-Type: audio/wav` and the
audio bytes directly. Failures return the usual JSON `error` and `code` fields:
`400` for invalid text, `401` for an invalid or revoked app session, `413` for
an oversized request, `429` for a Gemini rate/quota limit, `503` for missing
configuration or unavailable model access, `502` for an upstream failure or
invalid audio, and `504` for a timeout. Generated audio is validated in memory.
Reading text aloud does not create additional Memo, Chat, or Sheet records.

After restarting Flask, set `VOICEBRIDGE_TOKEN` to a current Flask access JWT
from Google sign-in, then request and save the audio:

```bash
curl --fail --silent --show-error -X POST http://127.0.0.1:5003/tts \
  -H "Authorization: Bearer $VOICEBRIDGE_TOKEN" \
  -H "Content-Type: application/json" \
  --data '{"text":"Hello! This is VoiceBridge."}' \
  --output instance/tts-api-demo.wav
```

On macOS, play the saved WAV with `afplay instance/tts-api-demo.wav`.
The standalone Python script and Flask route share `services/tts_service.py`.

## Data and Sessions

- SQLite stores users, session token hashes, conversations, messages,
  Memos, and attached Memo snapshots.
- App JWTs use HS256 and contain `sub` (app user ID), `sid` (login session ID),
  `iat`, `exp`, `iss`, and `aud`. Flask verifies the signature, issuer, audience,
  expiration, and active database session on every protected request.
- The Google ID token proves identity during login; the Flask JWT authorizes app
  requests. The one-use Google login nonce remains a separate 5-minute value.
- Logout revokes the current SQLite session immediately, even if its JWT has not expired.
- Google Sheets logs submitted user text for Chat and Memo modes.
- Saved history belongs to `user_id` and remains available after signing in again.
- Android keeps the Flask token in memory. A full app process restart
  requires signing in again.
- After upgrading from opaque Flask tokens, sign in again to receive a JWT.
  Existing user-owned chats and Memos remain available; no database reset is needed.
- A failed Sheet copy does not discard a saved Memo or completed Chat reply.
- API keys, service account credentials, local databases, and build outputs
  are excluded from version control.

## Testing

### Standalone Gemini TTS smoke test

Set `GEMINI_API_KEY`, `GEMINI_TTS_MODEL`, and `GEMINI_TTS_VOICE` in `.env`
using the Gemini section in `.env_example`. This test calls the live Gemini
Interactions API and uses the project's API quota:

```bash
uv run python -m scripts.tts "Hello! This is VoiceBridge." --output instance/tts-demo.wav
afplay instance/tts-demo.wav
```

The script validates the returned WAV and reports its duration, sample rate,
and generation time. `afplay` is the macOS playback command. Omit `--output`
to create a new timestamped file on each run; an existing file is never overwritten.
Generated test audio stays in the ignored `instance/` directory. The Flask
`POST /tts` endpoint also powers the Android Memo and Chat read-aloud controls.

Backend tests:

```bash
uv run python -m unittest discover -s tests -v
```

Android unit tests:

```bash
cd android
./gradlew :app:testDebugUnitTest
```

Android instrumented tests, with a device or emulator connected:

```bash
./gradlew :app:connectedDebugAndroidTest
```

Automated tests use mocked external services and test repositories.
Real Google sign-in and microphone behavior should also be checked on
the target emulator or device.
