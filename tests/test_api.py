"""Validate the HTTP contract and OpenAI failures without sending real audio to OpenAI."""

import io
import os
import tempfile
import unittest
import wave
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

import httpx2
from openai import OpenAI

from app import create_app


def recording():
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(16000)
        wav.writeframes(b"\x00\x00" * 160)
    return buffer.getvalue()


def test_app(client=None, config=None):
    # Keep tests independent of the user's .env and shell configuration.
    directory = tempfile.TemporaryDirectory(prefix="voicebridge-test-")
    unittest.addModuleCleanup(directory.cleanup)
    settings = {
        "TESTING": True,
        "DATABASE": str(Path(directory.name) / "test.sqlite3"),
        "GOOGLE_CLIENT_ID": "offline-test-client.apps.googleusercontent.com",
        "JWT_SECRET_KEY": "offline-test-signing-key-" + "a" * 48,
    }
    if config:
        settings.update(config)
    with patch("app.dotenv_values", return_value={}), patch.dict(os.environ, {}, clear=True):
        return create_app(settings, openai_client=client)


def json_payload(response):
    data = response.get_json()
    if not isinstance(data, dict):
        raise AssertionError("Expected a JSON object response.")
    return data


def login_user(http, *, google_sub="google-test-user"):
    challenge = http.post("/auth/google/challenge")
    if challenge.status_code != 201:
        raise AssertionError("Could not create a login challenge.")

    nonce = json_payload(challenge)["nonce"]
    claims = {
        "sub": google_sub,
        "nonce": nonce,
        "name": "Test User",
        "email": f"{google_sub}@example.test",
        "email_verified": True,
    }

    with patch(
        "services.auth_service.id_token.verify_oauth2_token",
        return_value=claims,
    ):
        response = http.post(
            "/auth/google",
            json={
                "id_token": "offline-google-id-token",
                "nonce": nonce,
            },
        )

    if response.status_code != 201:
        raise AssertionError("Test Google login failed.")

    return json_payload(response)


class TranscriptionApiTests(unittest.TestCase):
    def setUp(self):
        self.upstream = Mock()
        self.upstream.audio.transcriptions.create.return_value = SimpleNamespace(text="Hello.")
        self.app = test_app(self.upstream)
        self.http = self.app.test_client()
        self.token = login_user(self.http)["access_token"]
        self.headers = {"Authorization": f"Bearer {self.token}"}

    def upload(self, http=None, data=None, filename="recording.wav"):
        http = self.http if http is None else http
        token = self.token if http is self.http else login_user(http)["access_token"]
        return http.post(
            "/transcribe",
            headers={"Authorization": f"Bearer {token}"},
            data={"file": (io.BytesIO(recording() if data is None else data), filename)},
        )

    def test_health_does_not_call_openai(self):
        response = self.http.get("/health")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(json_payload(response), {"status": "ok"})
        self.upstream.audio.transcriptions.create.assert_not_called()

    def test_missing_invalid_expired_and_logged_out_sessions_are_rejected(self):
        for authorization in [None, "Bearer invalid", "Bearer " + "x" * 43]:
            with self.subTest(authorization=authorization):
                headers = {} if authorization is None else {"Authorization": authorization}
                response = self.http.post(
                    "/transcribe",
                    headers=headers,
                    data={"file": (io.BytesIO(recording()), "recording.wav")},
                )
                self.assertEqual(response.status_code, 401)
                self.assertEqual(json_payload(response)["code"], "unauthorized")
                self.assertEqual(response.headers["WWW-Authenticate"], "Bearer")

        from db import get_db

        with self.app.app_context():
            connection = get_db()
            with connection:
                connection.execute("UPDATE api_sessions SET expires_at = 0")
        self.assertEqual(self.upload().status_code, 401)

        self.token = login_user(self.http)["access_token"]
        headers = {"Authorization": f"Bearer {self.token}"}
        self.assertEqual(self.http.post("/auth/logout", headers=headers).status_code, 200)
        self.assertEqual(self.upload().status_code, 401)
        self.upstream.audio.transcriptions.create.assert_not_called()

    def test_upload_returns_text_and_preserves_audio(self):
        response = self.upload(filename="client-directory/recording.WAV")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(json_payload(response), {"text": "Hello."})
        self.assertEqual(response.headers["Cache-Control"], "no-store")
        self.upstream.audio.transcriptions.create.assert_called_once_with(
            model="gpt-transcribe", file=("audio.wav", recording(), "audio/wav")
        )

    def test_invalid_uploads_are_rejected_before_openai(self):
        cases = [
            ({"json": {"file": "recording.wav"}}, 415, "multipart_required"),
            ({"data": {}, "content_type": "multipart/form-data"}, 400, "file_required"),
            ({"data": {"file": (io.BytesIO(b"audio"), "")}}, 400, "file_required"),
            (
                {"data": {"file": (io.BytesIO(b"audio"), "notes.txt")}},
                415,
                "unsupported_audio_format",
            ),
            ({"data": {"file": (io.BytesIO(b""), "empty.wav")}}, 400, "empty_audio"),
            (
                {"data": {"file": [(io.BytesIO(b"a"), "a.wav"), (io.BytesIO(b"b"), "b.wav")]}},
                400,
                "file_required",
            ),
        ]
        for kwargs, status, code in cases:
            with self.subTest(code=code, status=status):
                response = self.http.post("/transcribe", headers=self.headers, **kwargs)
                self.assertEqual(response.status_code, status)
                self.assertEqual(json_payload(response)["code"], code)
        self.upstream.audio.transcriptions.create.assert_not_called()

    def test_audio_size_and_request_size_limits_return_json(self):
        with patch("services.stt_service.MAX_AUDIO_BYTES", 8):
            response = self.upload(data=b"a" * 9)
        self.assertEqual(response.status_code, 413)
        self.assertEqual(json_payload(response)["code"], "audio_too_large")
        self.app.config["MAX_CONTENT_LENGTH"] = 64
        response = self.upload()
        self.assertEqual(response.status_code, 413)
        self.assertEqual(json_payload(response)["code"], "audio_too_large")
        self.upstream.audio.transcriptions.create.assert_not_called()

    def test_missing_server_key_returns_service_unavailable(self):
        response = self.upload(http=test_app().test_client())
        self.assertEqual(response.status_code, 503)
        self.assertEqual(json_payload(response)["code"], "openai_not_configured")

    def test_non_string_upstream_text_is_rejected(self):
        self.upstream.audio.transcriptions.create.return_value = SimpleNamespace(text=None)
        response = self.upload()
        self.assertEqual(response.status_code, 502)
        self.assertEqual(json_payload(response)["code"], "invalid_transcript")

    def test_routing_errors_are_json(self):
        for path, status in [("/missing", 404), ("/transcribe", 405)]:
            with self.subTest(path=path):
                response = self.http.get(path)
                self.assertEqual(response.status_code, status)
                self.assertIsInstance(json_payload(response)["error"], str)

    def test_real_sdk_multipart_contract(self):
        requests_seen = []

        def respond(request):
            self.assertEqual(request.method, "POST")
            self.assertEqual(request.url.path, "/v1/audio/transcriptions")
            self.assertIn(b"gpt-transcribe", request.content)
            self.assertIn(b'filename="audio.wav"', request.content)
            self.assertIn(b"Content-Type: audio/wav", request.content)
            self.assertIn(recording(), request.content)
            requests_seen.append(request)
            return httpx2.Response(
                200, json={"text": "\u4f60\u597d", "languages": [{"code": "zh"}]}
            )

        with OpenAI(
            api_key="offline-test-key",
            http_client=httpx2.Client(transport=httpx2.MockTransport(respond)),
            max_retries=0,
        ) as client:
            response = self.upload(http=test_app(client).test_client())
        self.assertEqual(response.status_code, 200)
        self.assertEqual(json_payload(response), {"text": "\u4f60\u597d"})
        self.assertIn("\u4f60\u597d".encode("utf-8"), response.data)
        self.assertEqual(len(requests_seen), 1)

    def test_upstream_http_errors_are_mapped_without_leaking_details_or_retrying(self):
        cases = [(400, 400), (401, 503), (403, 503), (404, 503), (429, 429), (500, 502)]
        access_failures = {
            401: ("openai_auth_failed", "AuthenticationError"),
            403: ("openai_permission_denied", "PermissionDeniedError"),
            404: ("openai_model_unavailable", "NotFoundError"),
        }
        for upstream_status, expected_status in cases:
            with self.subTest(upstream_status=upstream_status):
                requests_seen = []

                def respond(request):
                    requests_seen.append(request)
                    return httpx2.Response(
                        upstream_status,
                        json={
                            "error": {
                                "message": "private-upstream-detail",
                                "type": "test_error",
                                "code": "test_upstream_code",
                            }
                        },
                        headers={"x-request-id": "req_offline_test"},
                    )

                with OpenAI(
                    api_key="offline-test-key",
                    http_client=httpx2.Client(transport=httpx2.MockTransport(respond)),
                    max_retries=0,
                ) as client:
                    app = test_app(client)
                    with patch("errors.logger.warning") as warning:
                        response = self.upload(http=app.test_client())
                self.assertEqual(response.status_code, expected_status)
                self.assertNotIn("private-upstream-detail", response.get_data(as_text=True))
                self.assertEqual(len(requests_seen), 1)
                if upstream_status in access_failures:
                    code, error_type = access_failures[upstream_status]
                    self.assertEqual(json_payload(response)["code"], code)
                    warning.assert_called_once()
                    template, *values = warning.call_args.args
                    diagnostic = template % tuple(values)
                    self.assertIn(f"type={error_type}", diagnostic)
                    self.assertIn(f"status={upstream_status}", diagnostic)
                    self.assertIn("code=test_upstream_code", diagnostic)
                    self.assertIn("request_id=req_offline_test", diagnostic)
                    self.assertNotIn("private-upstream-detail", diagnostic)
                    self.assertNotIn("offline-test-key", diagnostic)

    def test_upstream_connection_and_timeout_errors(self):
        for exception, expected in [(httpx2.ConnectError, 502), (httpx2.ReadTimeout, 504)]:
            with self.subTest(exception=exception.__name__):

                def respond(request):
                    raise exception("simulated transport failure", request=request)

                with OpenAI(
                    api_key="offline-test-key",
                    http_client=httpx2.Client(transport=httpx2.MockTransport(respond)),
                    max_retries=0,
                ) as client:
                    response = self.upload(http=test_app(client).test_client())
                self.assertEqual(response.status_code, expected)


if __name__ == "__main__":
    unittest.main()
