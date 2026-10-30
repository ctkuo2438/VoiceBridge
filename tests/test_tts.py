"""Verify authenticated WAV responses and Gemini failures without API charges."""

import base64
import hashlib
import io
import json
import unittest
import wave
from unittest.mock import patch

import requests

from db import get_db
from test_api import json_payload, login_user, test_app


def wav_audio():
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(24000)
        audio.writeframes(b"\x01\x00" * 240)
    return buffer.getvalue()


def gemini_payload(audio):
    return {
        "status": "completed",
        "steps": [{
            "type": "model_output",
            "content": [{"type": "audio", "mime_type": "audio/wav", "data": base64.b64encode(audio).decode("ascii")}],
        }],
    }


def gemini_response(payload=None, *, status=200, raw=None):
    response = requests.Response()
    response.status_code = status
    response.headers["Content-Type"] = "application/json"
    response._content = json.dumps(payload).encode("utf-8") if raw is None else raw
    response._content_consumed = True
    return response


class TextToSpeechApiTests(unittest.TestCase):
    def setUp(self):
        self.app = test_app(config={
            "GEMINI_API_KEY": "offline-gemini-key-not-for-production",
            "GEMINI_TTS_MODEL": "gemini-3.8-flash-tts",
            "GEMINI_TTS_VOICE": "Kore",
        })
        self.http = self.app.test_client()
        self.token = login_user(self.http)["access_token"]
        self.headers = {"Authorization": f"Bearer {self.token}"}
        self.audio = wav_audio()
        self.provider = self.enterContext(patch("services.tts_service.requests.post"))
        self.provider.return_value = gemini_response(gemini_payload(self.audio))

    def speak(self, text="Hello from VoiceBridge.", **kwargs):
        return self.http.post("/tts", headers=self.headers, json={"text": text}, **kwargs)

    def test_returns_exact_wav_and_uses_only_server_credentials(self):
        response = self.speak("  Hello from VoiceBridge.  ")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.data, self.audio)
        self.assertEqual(response.mimetype, "audio/wav")
        self.assertEqual(response.content_length, len(self.audio))
        self.assertEqual(response.headers["Cache-Control"], "no-store")
        self.assertEqual(response.headers["Content-Disposition"], 'inline; filename="speech.wav"')
        self.assertEqual(response.headers["X-Content-Type-Options"], "nosniff")

        self.provider.assert_called_once()
        args, kwargs = self.provider.call_args
        self.assertEqual(args, ("https://generativelanguage.googleapis.com/v1beta/interactions",))
        self.assertEqual(kwargs["headers"], {"x-goog-api-key": self.app.config["GEMINI_API_KEY"]})
        self.assertEqual(kwargs["timeout"], (10, 120))
        self.assertFalse(kwargs["allow_redirects"])
        self.assertTrue(kwargs["stream"])
        self.assertEqual(kwargs["json"], {
            "model": "gemini-3.8-flash-tts",
            "input": [{"type": "user_input", "content": [{"type": "text", "text": "Hello from VoiceBridge."}]}],
            "response_format": {"type": "audio"},
            "generation_config": {"speech_config": [{"voice": "Kore"}]},
            "store": False,
        })
        self.assertNotIn(self.token, json.dumps(kwargs))

    def test_request_fields_cannot_override_the_server_key_model_or_voice(self):
        self.app.config.update(GEMINI_TTS_MODEL="gemini-3.8-flash-lite-tts", GEMINI_TTS_VOICE="Puck")
        response = self.http.post("/tts", headers=self.headers, json={
            "text": "Read this note.", "api_key": "client-key", "model": "client-model", "voice": "client-voice",
        })
        self.assertEqual(response.status_code, 200)
        kwargs = self.provider.call_args.kwargs
        self.assertEqual(kwargs["headers"]["x-goog-api-key"], self.app.config["GEMINI_API_KEY"])
        self.assertEqual(kwargs["json"]["model"], "gemini-3.8-flash-lite-tts")
        self.assertEqual(kwargs["json"]["generation_config"]["speech_config"], [{"voice": "Puck"}])

    def test_missing_invalid_expired_and_revoked_sessions_never_call_gemini(self):
        for authorization in (None, "Bearer invalid", "Bearer " + "x" * 43):
            headers = {} if authorization is None else {"Authorization": authorization}
            response = self.http.post("/tts", headers=headers, json={"text": "Hello"})
            self.assertEqual(response.status_code, 401)
            self.assertEqual(response.headers["WWW-Authenticate"], "Bearer")
        with self.app.app_context():
            connection = get_db()
            with connection:
                connection.execute("UPDATE api_sessions SET expires_at = 0")
        self.assertEqual(self.speak().status_code, 401)
        self.token = login_user(self.http)["access_token"]
        self.headers = {"Authorization": f"Bearer {self.token}"}
        self.assertEqual(self.http.post("/auth/logout", headers=self.headers).status_code, 200)
        self.assertEqual(self.speak().status_code, 401)
        self.provider.assert_not_called()

    def test_invalid_json_and_text_are_rejected_before_gemini(self):
        for payload in (None, [], "Hello", {}, {"text": None}, {"text": 3}, {"text": []}, {"text": ""}, {"text": " \n\t "}):
            with self.subTest(payload_type=type(payload).__name__):
                response = self.http.post("/tts", headers=self.headers, json=payload)
                self.assertEqual(response.status_code, 400)
                self.assertTrue(response.is_json)
        for kwargs in ({"data": '{"text":', "content_type": "application/json"}, {"data": {"text": "Hello"}}):
            self.assertEqual(self.http.post("/tts", headers=self.headers, **kwargs).status_code, 400)
        response = self.speak("a" * 4001)
        self.assertEqual(response.status_code, 400)
        self.assertEqual(json_payload(response)["code"], "text_too_long")
        self.provider.assert_not_called()

    def test_boundary_length_and_unicode_text_reach_gemini(self):
        text = "\u4f60\u597d" * 2000
        self.assertEqual(self.speak(text).status_code, 200)
        self.assertEqual(self.provider.call_args.kwargs["json"]["input"][0]["content"][0]["text"], text)

    def test_oversized_body_has_a_tts_specific_json_error(self):
        response = self.speak("a" * (64 * 1024))
        self.assertEqual(response.status_code, 413)
        self.assertEqual(json_payload(response)["code"], "tts_request_too_large")
        self.provider.assert_not_called()

    def test_missing_gemini_configuration_does_not_break_other_endpoints(self):
        for name in ("GEMINI_API_KEY", "GEMINI_TTS_MODEL", "GEMINI_TTS_VOICE"):
            original = self.app.config[name]
            for value in ("", None):
                with self.subTest(setting=name, value=value):
                    self.app.config[name] = value
                    response = self.speak()
                    self.assertEqual(response.status_code, 503)
                    self.assertEqual(json_payload(response)["code"], "gemini_not_configured")
            self.app.config[name] = original
        self.assertEqual(self.http.get("/health").status_code, 200)
        self.assertEqual(self.http.get("/auth/me", headers=self.headers).status_code, 200)
        self.provider.assert_not_called()

    def test_gemini_http_failures_never_leak_details_or_retry(self):
        for upstream, expected, code in (
            (400, 400, "tts_request_rejected"),
            (401, 503, "gemini_auth_failed"),
            (403, 503, "gemini_permission_denied"),
            (404, 503, "gemini_model_unavailable"),
            (408, 504, "gemini_timeout"),
            (429, 429, "gemini_rate_limit"),
            (500, 502, "gemini_failed"),
            (503, 502, "gemini_failed"),
            (504, 504, "gemini_timeout"),
            (302, 502, "gemini_failed"),
        ):
            with self.subTest(upstream=upstream):
                self.provider.reset_mock()
                secret = self.app.config["GEMINI_API_KEY"]
                self.provider.return_value = gemini_response({"error": {"message": f"private-provider-detail {secret}"}}, status=upstream)
                with self.assertLogs("services.tts_service", level="WARNING") as logs:
                    response = self.speak()
                self.assertEqual(response.status_code, expected)
                self.assertEqual(json_payload(response)["code"], code)
                self.assertNotIn(secret, response.get_data(as_text=True) + str(logs.output))
                self.assertNotIn("private-provider-detail", response.get_data(as_text=True) + str(logs.output))
                self.provider.assert_called_once()

    def test_network_failure_and_timeout_are_json_and_hide_exception_details(self):
        for exception, expected, code in (
            (requests.Timeout("private-provider-detail"), 504, "gemini_timeout"),
            (requests.ConnectionError("private-provider-detail"), 502, "gemini_unreachable"),
        ):
            with self.subTest(exception=type(exception).__name__):
                self.provider.reset_mock()
                self.provider.side_effect = exception
                with self.assertLogs("services.tts_service", level="WARNING") as logs:
                    response = self.speak()
                self.assertEqual(response.status_code, expected)
                self.assertEqual(json_payload(response)["code"], code)
                self.assertNotIn("private-provider-detail", response.get_data(as_text=True) + str(logs.output))
                self.provider.assert_called_once()

    def test_incomplete_or_invalid_audio_responses_are_rejected(self):
        valid = gemini_payload(self.audio)
        invalid = [
            None,
            [],
            {},
            {**valid, "status": "incomplete"},
            {**valid, "steps": None},
            {**valid, "steps": [{"type": "model_output", "content": None}]},
            {**valid, "steps": [{"type": "model_output", "content": []}]},
            {**valid, "steps": [{"type": "model_output", "content": [{"type": "audio", "data": "%%%"}]}]},
            gemini_payload(b"not a WAV recording"),
            gemini_payload(b""),
            gemini_payload(self.audio[:-2]),
        ]
        for index, payload in enumerate(invalid):
            with self.subTest(case=index):
                self.provider.return_value = gemini_response(payload)
                response = self.speak()
                self.assertEqual(response.status_code, 502)
                self.assertEqual(json_payload(response)["code"], "invalid_speech_response")
        self.provider.return_value = gemini_response(raw=b"not-json")
        self.assertEqual(self.speak().status_code, 502)

    def test_upstream_json_and_decoded_audio_have_size_limits(self):
        with patch("services.tts_service.MAX_RESPONSE_BYTES", 16):
            response = self.speak()
            self.assertEqual(response.status_code, 502)
            self.assertEqual(json_payload(response)["code"], "invalid_speech_response")
        self.provider.return_value = gemini_response(gemini_payload(self.audio))
        with patch("services.tts_service.MAX_AUDIO_BYTES", len(self.audio) - 1):
            response = self.speak()
            self.assertEqual(response.status_code, 502)
            self.assertEqual(json_payload(response)["code"], "invalid_speech_response")

    def test_tts_does_not_create_memos_chat_messages_or_sheet_rows(self):
        with self.app.app_context():
            before = get_db().iterdump()
            before_hash = hashlib.sha256("\n".join(before).encode()).hexdigest()
        with patch("services.sheets_service.append_prompt") as sheet_write:
            self.assertEqual(self.speak("Read my existing memo.").status_code, 200)
            sheet_write.assert_not_called()
        with self.app.app_context():
            after = get_db().iterdump()
            self.assertEqual(hashlib.sha256("\n".join(after).encode()).hexdigest(), before_hash)


if __name__ == "__main__":
    unittest.main()
