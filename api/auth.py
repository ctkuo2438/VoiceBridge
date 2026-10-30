"""HTTP endpoints for Google sign-in."""

from flask import Blueprint, current_app, g, jsonify, make_response, render_template

from errors import ApiError, json_body
from services import auth_service, session_service
from api.sessions import require_session

bp = Blueprint("auth", __name__, url_prefix="/auth")


# validate current user
@bp.get("/me")
@require_session
def me():
    session = g.chat_session
    return jsonify(
        user={
            "id": session["user_id"],
            "name": session["name"],
            "email": session["email"],
            "avatar_url": session["avatar_url"],
        }
    )


# Get Client ID and one-time nonce
@bp.post("/google/challenge")
def google_challenge():
    result = auth_service.create_google_challenge(
        current_app.config["GOOGLE_CLIENT_ID"]
    )
    return jsonify(**result), 201


# Verify Google's ID token and nonce, then return a Flask-signed app access JWT.
@bp.post("/google")
def google_login():
    data = json_body()
    google_token = data.get("id_token")
    nonce = data.get("nonce")

    if not isinstance(google_token, str) or not isinstance(nonce, str):
        raise ApiError(
            400,
            "invalid_request",
            "Send id_token and nonce as strings.",
        )

    result = auth_service.complete_google_login(
        google_token,
        nonce,
        client_id=current_app.config["GOOGLE_CLIENT_ID"],
        ttl_seconds=current_app.config["SESSION_TTL_SECONDS"],
    )
    return jsonify(**result), 201


@bp.get("/test")
def google_login_test():
    response = make_response(render_template("login_test.html"))
    response.headers["Cross-Origin-Opener-Policy"] = "same-origin-allow-popups"
    response.headers["Referrer-Policy"] = "no-referrer-when-downgrade"
    return response


# Invalidate the current Flask session
@bp.post("/logout")
@require_session
def logout():
    session_service.logout(g.chat_session["id"])
    return jsonify(message="Logged out.")
