"""Exercise Python client -> real local Flask HTTP -> mocked OpenAI HTTP."""

import contextlib
import io
import os
import threading
import unittest
from unittest.mock import patch

import httpx2
from openai import OpenAI
from test_api import login_user, recording, test_app
from test_chat import ai_response
from werkzeug.serving import WSGIRequestHandler, make_server

from scripts import chat_client, stt


class QuietRequestHandler(WSGIRequestHandler):
    def log(self, *_args, **_kwargs):
        pass


class ProxyTests(unittest.TestCase):
    def test_signed_in_chat_client_keeps_voice_history_and_starts_new_conversations(self):
        chat_inputs = []
        transcription_requests = []

        def respond(request):
            import json

            if request.url.path == "/v1/audio/transcriptions":
                self.assertIn(recording(), request.content)
                transcription_requests.append(request)
                return httpx2.Response(200, json={"text": "Continue by voice"})

            self.assertEqual(request.url.path, "/v1/responses")
            chat_inputs.append(json.loads(request.content)["input"])
            response = ai_response("Reply through Flask.")
            return httpx2.Response(
                200,
                json={
                    "id": "resp_test",
                    "object": "response",
                    "created_at": 0,
                    "status": "completed",
                    "model": "gpt-5.6-sol",
                    "output": [item.model_dump() for item in response.output],
                },
            )

        with OpenAI(
            api_key="offline-server-key",
            http_client=httpx2.Client(transport=httpx2.MockTransport(respond)),
            max_retries=0,
        ) as client:
            app = test_app(client)
            token = login_user(app.test_client())["access_token"]
            server = make_server(
                "127.0.0.1", 0, app, request_handler=QuietRequestHandler
            )
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            output = io.StringIO()
            try:
                with (
                    patch.dict(os.environ, {}, clear=True),
                    patch("scripts.http_client.getpass", return_value=token) as token_prompt,
                    patch(
                        "scripts.chat_client.record_microphone",
                        return_value=("microphone.wav", recording(), "audio/wav"),
                    ) as microphone,
                    patch(
                        "api.chat.append_prompt",
                        return_value={"updated_range": "Logs!A2:D2", "updated_rows": 1},
                    ) as sheet_append,
                    patch(
                        "scripts.chat_client.input",
                        side_effect=[
                            "Hello",
                            "/mic",
                            "",
                            "y",
                            "/new",
                            "Translate into Spanish: Hello",
                            "/quit",
                        ],
                    ),
                    patch(
                        "sys.argv",
                        [
                            "scripts/chat_client.py",
                            "--api-url",
                            f"http://127.0.0.1:{server.server_port}",
                        ],
                    ),
                    contextlib.redirect_stdout(output),
                ):
                    chat_client.main()
                self.assertEqual(output.getvalue().count("Assistant: Reply through Flask."), 3)
                self.assertIn("Signed in as Test User.", output.getvalue())
                self.assertIn("Recognized: Continue by voice", output.getvalue())
                token_prompt.assert_called_once()
                microphone.assert_called_once()
                self.assertEqual(len(transcription_requests), 1)
                self.assertEqual(len(chat_inputs), 3)
                self.assertEqual(len(chat_inputs[0]), 1)
                self.assertEqual(len(chat_inputs[1]), 4)
                self.assertEqual(chat_inputs[1][-1]["content"], "Continue by voice")
                self.assertEqual(len(chat_inputs[2]), 1)
                self.assertEqual(sheet_append.call_count, 3)
                self.assertEqual(
                    [call.kwargs["prompt"] for call in sheet_append.call_args_list],
                    ["Hello", "Continue by voice", "Translate into Spanish: Hello"],
                )
            finally:
                server.shutdown()
                thread.join(timeout=5)
                server.server_close()

    def test_microphone_client_uses_flask_without_an_openai_key(self):
        upstream_requests = []

        def respond(request):
            self.assertIn(recording(), request.content)
            upstream_requests.append(request)
            return httpx2.Response(200, json={"text": "Hello through Flask."})

        with OpenAI(
            api_key="offline-server-key",
            http_client=httpx2.Client(transport=httpx2.MockTransport(respond)),
            max_retries=0,
        ) as client:
            app = test_app(client)
            token = login_user(app.test_client())["access_token"]
            server = make_server(
                "127.0.0.1", 0, app, request_handler=QuietRequestHandler
            )
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            output = io.StringIO()
            try:
                with (
                    patch.dict(os.environ, {}, clear=True),
                    patch("scripts.http_client.getpass", return_value=token) as token_prompt,
                    patch("scripts.stt.load_dotenv") as load_env,
                    patch(
                        "scripts.stt.OpenAI",
                        side_effect=AssertionError("Client must not call OpenAI"),
                    ),
                    patch(
                        "scripts.stt.record_microphone",
                        return_value=("microphone.wav", recording(), "audio/wav"),
                    ),
                    patch(
                        "sys.argv",
                        [
                            "scripts/stt.py",
                            "--mic",
                            "--api-url",
                            f"http://127.0.0.1:{server.server_port}",
                        ],
                    ),
                    contextlib.redirect_stdout(output),
                    contextlib.redirect_stderr(io.StringIO()),
                ):
                    stt.main()
                    load_env.assert_called_once_with(stt.PROJECT_DIR / ".env")
                self.assertEqual(output.getvalue().strip(), "Hello through Flask.")
                token_prompt.assert_called_once()
                self.assertEqual(len(upstream_requests), 1)
            finally:
                server.shutdown()
                thread.join(timeout=5)
                server.server_close()

    def test_client_reports_flask_errors(self):
        import requests

        response = requests.Response()
        response.status_code = 400
        response._content = b'{"error":"No audio was uploaded.","code":"file_required"}'
        with (
            requests.Session() as client,
            patch.object(client, "post", return_value=response),
        ):
            with self.assertRaisesRegex(ValueError, "No audio was uploaded"):
                stt.transcribe_via_api(
                    "http://127.0.0.1:5003", ("audio.wav", b"audio"), client=client
                )


if __name__ == "__main__":
    unittest.main()
