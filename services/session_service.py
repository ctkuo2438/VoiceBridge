"""Anonymous-token generation, hashing, expiry checks, and database lookup."""

import hashlib
import re
import secrets
import time
from uuid import uuid4

from db import (
    create_anonymous_session,
    expire_session,
    find_session,
    save_google_session,
)
from errors import ApiError

TOKEN_PATTERN = re.compile(r"[A-Za-z0-9_-]{43}\Z")


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("ascii")).hexdigest()


def create_session(ttl_seconds: int):
    token = secrets.token_urlsafe(32) # generate random session token
    # store the token’s hash and expiration time in SQLite
    create_anonymous_session(str(uuid4()), hash_token(token), int(time.time()) + ttl_seconds)
    return {"access_token": token, "token_type": "Bearer", "expires_in": ttl_seconds}



def resolve_session(token: str):
    if not TOKEN_PATTERN.fullmatch(token):
        raise ApiError(
            401,
            "unauthorized",
            "A valid Flask session token is required.",
        )

    chat_session = find_session(hash_token(token), int(time.time()))
    if chat_session is None:
        raise ApiError(
            401,
            "unauthorized",
            "Your session expired or you logged out. Sign in with Google.",
        )

    return chat_session



# After Google's verification is successful, Flask sends its own session token
def create_authenticated_session(profile: dict, ttl_seconds: int) -> dict:
    token = secrets.token_urlsafe(32)

    user = save_google_session(
        profile=profile,
        session_id=str(uuid4()),
        token_hash=hash_token(token),
        expires_at=int(time.time()) + ttl_seconds,
    )

    return {
        "access_token": token,
        "token_type": "Bearer",
        "expires_in": ttl_seconds,
        "user": user,
    }


def logout(session_id: str):
    expire_session(session_id)