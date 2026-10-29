"""Bearer authentication and rejection of anonymous session creation."""

from functools import wraps

from flask import Blueprint, g, request

from errors import ApiError
from services import session_service

bp = Blueprint("sessions", __name__)


@bp.post("/sessions")
def create_session():
    raise ApiError(
        403,
        "google_login_required",
        "Anonymous sessions are disabled. Sign in with Google.",
    )


def require_session(view):
    @wraps(view)
    def wrapped(*args, **kwargs):
        parts = request.headers.get("Authorization", "").split()
        if len(parts) != 2 or parts[0].lower() != "bearer":
            raise ApiError(
                401,
                "unauthorized",
                "Sign in with Google and send the Flask session Bearer token.",
            )

        g.chat_session = session_service.resolve_session(parts[1])
        return view(*args, **kwargs)

    return wrapped
