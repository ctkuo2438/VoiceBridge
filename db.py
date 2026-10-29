"""SQLite schema, request-scoped connections, and user data persistence."""

import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

from flask import current_app, g

from errors import ApiError

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    google_sub TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    email TEXT,
    avatar_url TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS google_login_challenges (
    nonce_hash TEXT PRIMARY KEY,
    expires_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_google_challenge_expiry
ON google_login_challenges(expires_at);

CREATE TABLE IF NOT EXISTS api_sessions (
    id TEXT PRIMARY KEY,
    token_hash TEXT NOT NULL UNIQUE,
    user_id TEXT,
    created_at TEXT NOT NULL,
    expires_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sessions_expiry ON api_sessions(expires_at);

CREATE TABLE IF NOT EXISTS conversations (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES api_sessions(id),
    user_id TEXT,
    title TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_conversations_session ON conversations(session_id, updated_at);

CREATE INDEX IF NOT EXISTS idx_conversations_user ON conversations(user_id, updated_at);

CREATE TABLE IF NOT EXISTS messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK(role IN ('user', 'assistant')),
    content TEXT NOT NULL,
    created_at TEXT NOT NULL,
    api_items TEXT
);

CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages(conversation_id, id);

CREATE TABLE IF NOT EXISTS memos (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id),
    request_id TEXT,
    prompt TEXT NOT NULL CHECK(length(trim(prompt)) > 0),
    created_at TEXT NOT NULL,
    sheet_status TEXT NOT NULL DEFAULT 'pending'
        CHECK(sheet_status IN ('pending', 'saved', 'unconfirmed')),
    sheet_result TEXT,
    UNIQUE(user_id, request_id)
);

CREATE INDEX IF NOT EXISTS idx_memos_user_created ON memos(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS conversation_memos (
    conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    memo_id TEXT NOT NULL,
    prompt TEXT NOT NULL,
    memo_created_at TEXT NOT NULL,
    position INTEGER NOT NULL,
    PRIMARY KEY (conversation_id, memo_id),
    UNIQUE(conversation_id, position)
);
"""


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def get_db() -> sqlite3.Connection:
    if "db" not in g:
        connection = sqlite3.connect(current_app.config["DATABASE"], timeout=10)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        g.db = connection
    return g.db


def close_db(_error=None):
    connection = g.pop("db", None)
    if connection is not None:
        connection.close()


def init_db(app):
    Path(app.config["DATABASE"]).parent.mkdir(parents=True, exist_ok=True)
    app.teardown_appcontext(close_db)
    with app.app_context():
        connection = get_db()
        connection.execute("PRAGMA journal_mode = WAL")
        connection.executescript(SCHEMA)


def create_anonymous_session(session_id: str, token_hash: str, expires_at: int):
    connection = get_db()
    with connection:
        connection.execute(
            """INSERT INTO api_sessions (id, token_hash, user_id, created_at, expires_at)
               VALUES (?, ?, NULL, ?, ?)""",
            (session_id, token_hash, utc_now(), expires_at),
        )


# JOIN users: The session must correspond to a user and must not have expired
def find_session(token_hash: str, now: int):
    return (
        get_db()
        .execute(
            """SELECT api_sessions.*, users.name, users.email, users.avatar_url
               FROM api_sessions
               JOIN users ON users.id = api_sessions.user_id
               WHERE api_sessions.token_hash = ?
                 AND api_sessions.expires_at > ?""",
            (token_hash, now),
        )
        .fetchone()
    )


def get_conversation(conversation_id: str, user_id: str):
    row = (
        get_db()
        .execute(
            "SELECT * FROM conversations WHERE id = ? AND user_id = ?",
            (conversation_id, user_id),
        )
        .fetchone()
    )
    if row is None:
        raise ApiError(404, "conversation_not_found", "Conversation not found.")
    return row


def conversation_history(conversation_id: str, max_messages: int = 100):
    rows = (
        get_db()
        .execute(
            "SELECT role, content, api_items FROM messages WHERE conversation_id = ? ORDER BY id DESC LIMIT ?",
            (conversation_id, max_messages),
        )
        .fetchall()
    )
    history = []
    for row in reversed(rows):
        if row["api_items"]:
            history.extend(json.loads(row["api_items"]))
        else:
            history.append({"role": row["role"], "content": row["content"]})
    return history


def public_message(row):
    return {key: row[key] for key in ("id", "role", "content", "created_at")}


def save_turn(chat_session, existing, message: str, reply: str, api_items: str, memo_context=None):
    """Commit both messages together after OpenAI finishes; never hold a lock during the API call."""
    connection = get_db()
    conversation_id = existing["id"] if existing is not None else str(uuid4())
    timestamp = utc_now()
    with connection:
        connection.execute("BEGIN IMMEDIATE")
        if existing is not None:
            updated = connection.execute(
                """UPDATE conversations SET version = version + 1, updated_at = ?
                   WHERE id = ? AND user_id = ? AND version = ?""",
                (
                    timestamp,
                    conversation_id,
                    chat_session["user_id"],
                    existing["version"],
                ),
            )
            if updated.rowcount != 1:
                raise ApiError(
                    409, "conversation_changed", "Conversation changed. Reload and try again."
                )
        else:
            connection.execute(
                """INSERT INTO conversations
                   (id, session_id, user_id, title, created_at, updated_at, version)
                   VALUES (?, ?, ?, ?, ?, ?, 1)""",
                (
                    conversation_id,
                    chat_session["id"],
                    chat_session["user_id"],
                    message[:80],
                    timestamp,
                    timestamp,
                ),
            )
        ids = []
        for role, content, items in (("user", message, None), ("assistant", reply, api_items)):
            inserted = connection.execute(
                """INSERT INTO messages (conversation_id, role, content, created_at, api_items)
                   VALUES (?, ?, ?, ?, ?)""",
                (conversation_id, role, content, timestamp, items),
            )
            ids.append(inserted.lastrowid)
        rows = connection.execute(
            "SELECT * FROM messages WHERE id IN (?, ?) ORDER BY id", ids
        ).fetchall()
        if existing is None and memo_context:
            # Snapshots remain usable if a source memo is later changed or removed.
            connection.executemany(
                """INSERT INTO conversation_memos
                   (conversation_id, memo_id, prompt, memo_created_at, position)
                   VALUES (?, ?, ?, ?, ?)""",
                [(conversation_id, memo["id"], memo["prompt"], memo["created_at"], position)
                 for position, memo in enumerate(memo_context)],
            )
    return conversation_id, [public_message(row) for row in rows]


def list_conversations(user_id: str):
    rows = (
        get_db()
        .execute(
            """SELECT id, title, created_at, updated_at FROM conversations
               WHERE user_id = ? ORDER BY updated_at DESC, id""",
            (user_id,),
        )
        .fetchall()
    )
    return [dict(row) for row in rows]


def list_messages(conversation_id: str, user_id: str):
    get_conversation(conversation_id, user_id)
    rows = (
        get_db()
        .execute(
            "SELECT * FROM messages WHERE conversation_id = ? ORDER BY id",
            (conversation_id,),
        )
        .fetchall()
    )
    return [public_message(row) for row in rows]


def delete_conversation(conversation_id: str, user_id: str):
    get_conversation(conversation_id, user_id)
    connection = get_db()
    with connection:
        connection.execute(
            "DELETE FROM conversations WHERE id = ? AND user_id = ?",
            (conversation_id, user_id),
        )


def public_memo(row):
    return {key: row[key] for key in ("id", "prompt", "created_at", "sheet_status")}


def create_memo(user_id: str, prompt: str, request_id: str | None = None):
    """Commit the note before contacting Sheets; repeated request IDs reuse one note."""
    connection = get_db()
    memo_id = str(uuid4())
    with connection:
        inserted = connection.execute(
            """INSERT INTO memos (id, user_id, request_id, prompt, created_at)
               VALUES (?, ?, ?, ?, ?)
               ON CONFLICT(user_id, request_id) DO NOTHING""",
            (memo_id, user_id, request_id, prompt, utc_now()),
        )
        if inserted.rowcount == 1:
            row = connection.execute("SELECT * FROM memos WHERE id = ?", (memo_id,)).fetchone()
            return row, True
        row = connection.execute(
            "SELECT * FROM memos WHERE user_id = ? AND request_id = ?",
            (user_id, request_id),
        ).fetchone()
        if row is None or row["prompt"] != prompt:
            raise ApiError(409, "memo_request_conflict", "This request ID belongs to a different memo.")
        return row, False


def save_memo_sheet_result(memo_id: str, user_id: str, result: dict):
    connection = get_db()
    status = "saved" if result.get("saved") is True else "unconfirmed"
    with connection:
        connection.execute(
            "UPDATE memos SET sheet_status = ?, sheet_result = ? WHERE id = ? AND user_id = ?",
            (status, json.dumps(result), memo_id, user_id),
        )
        return connection.execute(
            "SELECT * FROM memos WHERE id = ? AND user_id = ?", (memo_id, user_id)
        ).fetchone()


def list_memos(user_id: str):
    rows = get_db().execute(
        "SELECT * FROM memos WHERE user_id = ? ORDER BY created_at DESC, rowid DESC",
        (user_id,),
    ).fetchall()
    return [public_memo(row) for row in rows]


def get_memos_for_chat(user_id: str, memo_ids: list[str]):
    if not memo_ids:
        return []
    placeholders = ",".join("?" for _ in memo_ids)
    rows = get_db().execute(
        f"SELECT id, prompt, created_at FROM memos WHERE user_id = ? AND id IN ({placeholders})",
        (user_id, *memo_ids),
    ).fetchall()
    by_id = {row["id"]: dict(row) for row in rows}
    if len(by_id) != len(memo_ids):
        raise ApiError(404, "memo_not_found", "One or more memos were not found.")
    return [by_id[memo_id] for memo_id in memo_ids]


def conversation_memo_context(conversation_id: str, user_id: str):
    get_conversation(conversation_id, user_id)
    rows = get_db().execute(
        """SELECT memo_id AS id, prompt, memo_created_at AS created_at
           FROM conversation_memos WHERE conversation_id = ? ORDER BY position""",
        (conversation_id,),
    ).fetchall()
    return [dict(row) for row in rows]


# Save the hash value of the login nonce and clean up expired records
def store_google_challenge(nonce_hash: str, expires_at: int, now: int):
    connection = get_db()
    with connection:
        connection.execute(
            "DELETE FROM google_login_challenges WHERE expires_at <= ?",
            (now,),
        )
        connection.execute(
            "INSERT INTO google_login_challenges (nonce_hash, expires_at) VALUES (?, ?)",
            (nonce_hash, expires_at),
        )


# The nonce is deleted after confirming its validity, therefore it can only be used once
def consume_google_challenge(nonce_hash: str, now: int) -> bool:
    connection = get_db()
    with connection:
        deleted = connection.execute(
            """DELETE FROM google_login_challenges
               WHERE nonce_hash = ? AND expires_at > ?""",
            (nonce_hash, now),
        )
    return deleted.rowcount == 1


# Within the same database transaction, a new/updated user is added, 
#  and then a session is created that is bound to the user.
def save_google_session(
    profile: dict,
    session_id: str,
    token_hash: str,
    expires_at: int,
) -> dict:
    connection = get_db()
    timestamp = utc_now()

    with connection:
        connection.execute(
            """INSERT INTO users
               (id, google_sub, name, email, avatar_url, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(google_sub) DO UPDATE SET
                   name = excluded.name,
                   email = excluded.email,
                   avatar_url = excluded.avatar_url,
                   updated_at = excluded.updated_at""",
            (
                str(uuid4()),
                profile["google_sub"],
                profile["name"],
                profile["email"],
                profile["avatar_url"],
                timestamp,
                timestamp,
            ),
        )

        user = connection.execute(
            "SELECT id, name, email, avatar_url FROM users WHERE google_sub = ?",
            (profile["google_sub"],),
        ).fetchone()

        if user is None:
            raise ApiError(500, "user_save_failed", "Could not save the user.")

        connection.execute(
            """INSERT INTO api_sessions
               (id, token_hash, user_id, created_at, expires_at)
               VALUES (?, ?, ?, ?, ?)""",
            (session_id, token_hash, user["id"], timestamp, expires_at),
        )

    return dict(user)


# Set session to expire upon logout
def expire_session(session_id: str):
    connection = get_db()
    with connection:
        connection.execute(
            "UPDATE api_sessions SET expires_at = 0 WHERE id = ?",
            (session_id,),
        )
