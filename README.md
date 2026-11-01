# VoiceBridge

An Android application that turns speech into notes and conversations.

VoiceBridge supports Google sign-in, speech-to-text, chat and translation, persistent Memo history, and conversations based on selected Memos.

## Features

- **Google sign-in:** Authenticate with Google and receive a separate Flask-signed app access JWT.
- **Speech-to-text:** Record audio, transcribe it through Flask, and review or edit the recognized text.
- **Chat and translation:** Send typed or transcribed messages and continue saved conversations.
- **Memo library:** Save titled notes, edit saved Memos, and search personal titles and contents.
- **Recoverable input:** Retry a failed transcription from the same recording, and restore unfinished text drafts on this device after signing back into the same account.
- **Memos to Chat:** Summarize a note, extract action items, create Chinese-English key points, or ask a question, with sources linked to saved note paragraphs. Multiple notes can also be selected for a conversation.
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
| Persistence | SQLite (backend), Jetpack DataStore (local draft recovery) |
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

Record → transcribe → review or edit → optionally add a title → confirm save.

Flask saves the Memo in SQLite, then attempts to copy its text to Google Sheets.
The saved note appears in the user's Memo history, most recently updated first.

### Memo Library

The existing **Chat / Memo** tabs remain. In Memo mode:

- Add an optional title of up to 120 Unicode characters. A blank title uses the
  first line of the note, limited to 80 characters, without an AI request.
- Search titles and note contents by keyword. Matching is case-insensitive and
  runs immediately over the user's loaded library; clearing the search restores
  the complete list and retains selected Memos.
- Tap **Edit memo** on a saved note to change its title or content, then tap
  **Save changes**. This updates the same Memo and keeps its creation timestamp.
  The unfinished new-Memo composer remains separate from saved-Memo edits.
- Saved-Memo edit drafts are also recovered locally. **Cancel** discards those
  edits. An uncertain update can retry the same changes; a version conflict asks
  the user to reload the saved version before editing again.

Existing conversations continue using the Memo snapshots saved when they started.
New conversations use the current contents of their selected Memos. Updating a
Memo's body leaves the earlier Google Sheet copy unchanged and marks it as
outdated in the app. Renaming a title alone does not invalidate the copied body.

Restarting Flask upgrades existing Memo tables in place, filling titles and
update timestamps without resetting notes, user ownership, request IDs, or chats.

### Voice or Text Chat

Type or record → review text → Send → receive an answer or translation.

SQLite stores the user message and AI reply. Follow-up messages use the
same `conversation_id` to retain conversation context.

### Memos to Chat

Each saved Memo offers four actions:

- **Key points:** Start a new Chat and summarize the note.
- **Action items:** Extract explicit tasks, keeping owners and deadlines only when stated.
- **Chinese / English:** Create concise bilingual key points in aligned pairs.
- **Ask this note:** Attach the note to a new Chat, preserving the current question;
  edit the question and press **Send** when ready.

The first three actions send a prepared question automatically. If Chat already has
an unsent question, the app asks before replacing it. Cancelling keeps the draft.
Free Chat remains available, and users can still select up to 10 Memos together.

Flask verifies that the selected Memos belong to the current user.
It saves their titles and text as conversation snapshots. Answers based on notes
include **Sources** buttons. Tap a source to see the associated answer text, the
exact original paragraph, and the full saved note. Later edits to a Memo do not
change earlier conversations or their references. Follow-up answers use the same
saved reference material.

Note-based replies use [OpenAI Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses).
Flask validates the returned note and paragraph IDs, then fills excerpts from the
saved references; the model cannot supply its own quote. General suggestions and
missing information are labeled. These checks verify the source exists and the
excerpt matches; users can review the cited paragraph to assess the answer.
Free Chat continues using ordinary text replies. The configured OpenAI model must
support the Responses API's strict JSON schema format for note-based replies.

Restarting Flask adds the answer-source and snapshot-title columns without
resetting existing notes or conversations. Older messages remain readable.

Google Sheets records the new question, without duplicating the attached notes.

### Local Drafts and Transcription Recovery

Chat drafts, Memo drafts, and edited speech-review text are automatically saved
on this device with Jetpack DataStore. The UI distinguishes a pending local
write, a completed local save, and a storage failure. These local drafts do not
send a Chat message or create a Memo; users still choose **Send** or confirm **Save**.

After a recording is stopped, its completed audio file is registered locally
before transcription begins. A failed request keeps the file and offers
**Retry transcription** and **Discard**. Retry uploads the same recording.
Once recognized text is committed locally, the source audio is deleted. Accepting
the text appends it to the intended draft and clears the speech review in one
local transaction, so reopening cannot append the same text twice.

Recovery files live in app-private, account-scoped `noBackupFilesDir` storage;
they are excluded from Android cloud backup and device transfer. Signing out
clears the in-memory workspace. Signing back into the same account restores its
unfinished input; a different account gets a separate workspace. Uninstalling
the app or clearing its storage removes these local drafts and recordings.

App authentication is still held in memory, so a full process restart requires
Google sign-in before local recovery is available. Recording while the app is
in the foreground is unchanged: leaving the foreground during an ongoing recording
cancels that unfinished recording. Completed recordings awaiting transcription
and saved draft text survive a process restart.

Memo recovery also retains the pending save request ID, allowing a retry after
an uncertain response without creating a second Memo. Chat recovery retains the
conversation and selected Memo context; an interrupted send requires checking
the conversation or explicitly starting a new one before sending again.

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
| PUT | /memos/{id} | Update the signed-in user's Memo using its current version |

Protected endpoints require:

```http
Authorization: Bearer <Flask access JWT>
```

Memo responses include `title`, `updated_at`, and `version`. `POST /memos`
continues to accept a legacy `prompt` / `request_id` body, with an optional
`title`. `PUT /memos/{id}` requires `title`, `prompt`, and the last loaded
integer `version`. A stale edit returns `409 memo_changed`; an identical retry
of the immediately completed update returns the same version without writing
again. `sheet_status` retains its existing values; `sheet_outdated: true`
identifies an earlier Sheet copy of edited content.

`POST /chat` accepts optional `memo_ids` when starting a conversation and an
optional `memo_action` (`summary`, `todos`, `bilingual`, or `ask`) with selected
notes. Only memo IDs are accepted from Android; Flask loads the owned note text.
Assistant messages may include `sections` with `text`, `kind`, and `sources`.
Each source includes `memo_id`, `memo_title`, `paragraph_id`, and the server-filled
`quote`. `GET /conversations/{id}/messages` returns the same sources and saved
`memo_context` so clients can reopen the original references.

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
