
"""Google ID-token verification for application sign-in."""

import secrets
import requests
import time
import db

from services import session_service

from collections.abc import Mapping
from functools import partial

from google.auth.exceptions import GoogleAuthError, TransportError
from google.auth.transport.requests import Request as GoogleRequest
from google.oauth2 import id_token

from errors import ApiError

GOOGLE_CHALLENGE_SECONDS = 300

def verify_google_token(
    token: str,
    *,
    client_id: str,
    expected_nonce: str,
) -> dict:
    if not client_id.strip():
        raise ApiError(
            503,
            "google_not_configured",
            "Set GOOGLE_CLIENT_ID in .env and restart Flask.",
        )

    if not isinstance(token, str) or not token.strip() or len(token) > 8192:
        raise ApiError(
            400,
            "invalid_google_token",
            "A Google ID token is required.",
        )

    try:
        with requests.Session() as session:
            transport = partial(
                GoogleRequest(session=session),
                timeout=15,
            )
            # Google verifies the token's signature, recipient, issuer, and expiration date
            claims = id_token.verify_oauth2_token(
                token.strip(),
                transport,
                audience=client_id,
            )

    except (TransportError, requests.RequestException) as error:
        raise ApiError(
            503,
            "google_unavailable",
            "Unable to verify Google sign-in. Try again later.",
        ) from error
    except (GoogleAuthError, ValueError, KeyError, TypeError) as error:
        raise ApiError(
            401,
            "invalid_google_token",
            "Google ID token is invalid or expired.",
        ) from error

    if not isinstance(claims, Mapping):
        raise ApiError(
            401,
            "invalid_google_token",
            "Google did not return a valid identity.",
        )

    subject = claims.get("sub")
    token_nonce = claims.get("nonce")

    if (
        not isinstance(subject, str)
        or not subject.strip()
        or len(subject) > 255
        or not isinstance(token_nonce, str)
        or not secrets.compare_digest(
            token_nonce.encode("utf-8"),
            expected_nonce.encode("utf-8"),
        )
    ):
        raise ApiError(
            401,
            "invalid_google_token",
            "Google sign-in could not be verified. Start sign-in again.",
        )

    name = claims.get("name")
    email = claims.get("email")
    picture = claims.get("picture")

    return {
        "google_sub": subject,
        "name": name if isinstance(name, str) and name.strip() else "Google user",
        "email": (
            email
            if isinstance(email, str) and claims.get("email_verified") is True
            else None
        ),
        "avatar_url": picture if isinstance(picture, str) else None,
    }


# Generate a valid 5-minute nonce
def create_google_challenge(client_id: str) -> dict:
    if not client_id.strip():
        raise ApiError(
            503,
            "google_not_configured",
            "Set GOOGLE_CLIENT_ID in .env and restart Flask.",
        )

    nonce = secrets.token_urlsafe(32)
    now = int(time.time())

    db.store_google_challenge(
        nonce_hash=session_service.hash_token(nonce),
        expires_at=now + GOOGLE_CHALLENGE_SECONDS,
        now=now,
    )

    return {
        "client_id": client_id,
        "nonce": nonce,
        "expires_in": GOOGLE_CHALLENGE_SECONDS,
    }


# After confirming that the nonce was issued by the backend, has not expired, 
#  and has not been used, verify the Google token, and finally establish the login session.
# The front-end sends this nonce when logging into Google, 
#  and the ID token returned by Google also includes it for the back-end to compare.
def complete_google_login(
    token: str,
    nonce: str,
    *,
    client_id: str,
    ttl_seconds: int,
) -> dict:
    if (
        not isinstance(nonce, str)
        or not session_service.TOKEN_PATTERN.fullmatch(nonce)
    ):
        raise ApiError(
            401,
            "google_login_expired",
            "Start Google sign-in again.",
        )

    valid_challenge = db.consume_google_challenge(
        session_service.hash_token(nonce),
        int(time.time()),
    )
    if not valid_challenge:
        raise ApiError(
            401,
            "google_login_expired",
            "The login challenge expired or was already used. Start sign-in again.",
        )

    profile = verify_google_token(
        token,
        client_id=client_id,
        expected_nonce=nonce,
    )

    return session_service.create_authenticated_session(profile, ttl_seconds)