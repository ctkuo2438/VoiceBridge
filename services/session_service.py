"""Issue and verify app JWTs while retaining database-backed session revocation."""

import hashlib
import re
import time
from uuid import uuid4

import jwt
from flask import current_app

from db import (
    expire_session,
    find_session,
    save_authenticated_session,
    save_google_user,
)
from errors import ApiError

JWT_ALGORITHM = "HS256"
MAX_JWT_LENGTH = 4096
JWT_PATTERN = re.compile(r"[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\Z")


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("ascii")).hexdigest()


def resolve_session(token: str):
    if len(token) > MAX_JWT_LENGTH or not JWT_PATTERN.fullmatch(token):
        raise ApiError(
            401,
            "unauthorized",
            "A valid Flask access JWT is required. Sign in with Google.",
        )

    try:
        claims = jwt.decode(
            token,
            current_app.config["JWT_SECRET_KEY"],
            algorithms=[JWT_ALGORITHM],
            issuer=current_app.config["JWT_ISSUER"],
            audience=current_app.config["JWT_AUDIENCE"],
            options={
                "require": ["sub", "sid", "iat", "exp", "iss", "aud"],
                "strict_aud": True,
            },
        )
        if (
            not isinstance(claims["sub"], str)
            or not claims["sub"]
            or not isinstance(claims["sid"], str)
            or not claims["sid"]
            or type(claims["iat"]) is not int
            or type(claims["exp"]) is not int
            or claims["exp"] <= claims["iat"]
        ):
            raise jwt.InvalidTokenError("Invalid app session claims.")
    except jwt.InvalidTokenError as error:
        raise ApiError(
            401,
            "unauthorized",
            "Your Flask access JWT is invalid or expired. Sign in with Google.",
        ) from error

    # A valid signature alone does not authorize a session that has been revoked.
    chat_session = find_session(hash_token(token), int(time.time()))
    if (
        chat_session is None
        or claims["sub"] != chat_session["user_id"]
        or claims["sid"] != chat_session["id"]
    ):
        raise ApiError(
            401,
            "unauthorized",
            "Your session expired or you logged out. Sign in with Google.",
        )

    return chat_session



def create_authenticated_session(profile: dict, ttl_seconds: int) -> dict:
    user = save_google_user(profile)
    session_id = str(uuid4())
    now = int(time.time())
    expires_at = now + ttl_seconds
    token = jwt.encode(
        {
            "sub": user["id"],
            "sid": session_id,
            "iat": now,
            "exp": expires_at,
            "iss": current_app.config["JWT_ISSUER"],
            "aud": current_app.config["JWT_AUDIENCE"],
        },
        current_app.config["JWT_SECRET_KEY"],
        algorithm=JWT_ALGORITHM,
    )

    save_authenticated_session(
        session_id=session_id,
        user_id=user["id"],
        token_hash=hash_token(token),
        expires_at=expires_at,
    )

    return {
        "access_token": token,
        "token_type": "Bearer",
        "expires_in": ttl_seconds,
        "user": user,
    }


def logout(session_id: str):
    expire_session(session_id)
