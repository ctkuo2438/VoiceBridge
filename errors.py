"""Shared JSON validation and error responses."""

import logging

from flask import current_app, jsonify, request
from openai import (
    APIConnectionError,
    APIError,
    APITimeoutError,
    AuthenticationError,
    BadRequestError,
    NotFoundError,
    PermissionDeniedError,
    RateLimitError,
)
from werkzeug.exceptions import HTTPException, RequestEntityTooLarge

logger = logging.getLogger(__name__)


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


def json_error(status: int, code: str, message: str):
    return jsonify(error=message, code=code), status


def json_body():
    data = request.get_json(silent=True)
    if not isinstance(data, dict):
        raise ApiError(400, "invalid_request", "Request body must be a JSON object.")
    return data


def openai_error(error: APIError, *, task: str) -> ApiError:
    if isinstance(error, APITimeoutError):
        return ApiError(504, "openai_timeout", f"The {task} request timed out.")
    if isinstance(error, RateLimitError):
        return ApiError(429, "openai_rate_limit", "OpenAI rate or quota limit reached.")
    if isinstance(error, (AuthenticationError, PermissionDeniedError, NotFoundError)):
        logger.warning(
            "OpenAI %s failed: type=%s status=%s code=%s request_id=%s",
            task,
            type(error).__name__,
            getattr(error, "status_code", None),
            getattr(error, "code", None),
            getattr(error, "request_id", None),
        )
        if isinstance(error, AuthenticationError):
            return ApiError(
                503,
                "openai_auth_failed",
                "OpenAI rejected the server API key. Update OPENAI_API_KEY and restart Flask.",
            )
        if isinstance(error, PermissionDeniedError):
            return ApiError(
                503,
                "openai_permission_denied",
                "OpenAI denied this request. Check project permissions and access restrictions.",
            )
        return ApiError(
            503,
            "openai_model_unavailable",
            "The configured OpenAI model or API resource is unavailable to this project.",
        )
    if isinstance(error, BadRequestError):
        if task == "transcription":
            return ApiError(
                400,
                "invalid_audio",
                "OpenAI could not process this recording. Check its audio format.",
            )
        return ApiError(400, "invalid_prompt", "OpenAI could not process this chat request.")
    if isinstance(error, APIConnectionError):
        return ApiError(502, "openai_unreachable", f"Unable to reach the {task} service.")
    logger.warning("OpenAI %s failed: %s", task, type(error).__name__)
    return ApiError(502, "openai_failed", f"Unable to complete this {task} request right now.")


def register_error_handlers(app):
    @app.errorhandler(ApiError)
    def api_error(error: ApiError):
        response, status = json_error(error.status, error.code, error.message)
        if status == 401:
            response.headers["WWW-Authenticate"] = "Bearer"
        return response, status

    @app.errorhandler(RequestEntityTooLarge)
    def upload_too_large(_error):
        return json_error(413, "audio_too_large", "The audio file or upload request is too large.")

    @app.errorhandler(HTTPException)
    def http_error(error: HTTPException):
        return json_error(
            error.code or 500,
            error.name.lower().replace(" ", "_"),
            error.description or error.name,
        )

    @app.errorhandler(Exception)
    def unexpected_error(error: Exception):
        current_app.logger.error("Unhandled backend error: %s", type(error).__name__)
        return json_error(500, "internal_error", "An internal server error occurred.")
