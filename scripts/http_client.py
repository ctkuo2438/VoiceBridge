"""Shared HTTP and authentication helpers for Flask test clients."""

from getpass import getpass

import requests


def request_json(client, method, url, **kwargs):
    try:
        with client.request(method, url, timeout=(10, 180), **kwargs) as response:
            try:
                payload = response.json()
            except ValueError as error:
                raise ValueError("Flask did not return a JSON response.") from error

            if not response.ok:
                message = payload.get("error") if isinstance(payload, dict) else None
                raise ValueError(message or f"Flask returned HTTP {response.status_code}.")

            if not isinstance(payload, dict):
                raise ValueError("Flask returned an invalid response.")

            return payload

    except requests.Timeout as error:
        raise ValueError("The Flask request timed out.") from error
    except requests.RequestException as error:
        raise ValueError("Cannot reach Flask. Start it with: uv run python app.py") from error


def authenticate_client(client, api_url):
    token = getpass("Flask access token: ").strip()
    if not token:
        raise ValueError("A Flask access token is required.")

    client.headers["Authorization"] = f"Bearer {token}"

    payload = request_json(
        client,
        "GET",
        api_url.rstrip("/") + "/auth/me",
    )
    user = payload.get("user")

    if not isinstance(user, dict) or not isinstance(user.get("id"), str):
        raise ValueError("Flask did not return a valid user.")

    return user