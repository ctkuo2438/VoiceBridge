"""Chat history, signed-in user ownership, transactions, and SDK request contracts."""

import hashlib
import json
import sqlite3
import unittest
from contextlib import closing
from types import SimpleNamespace
from unittest.mock import Mock, patch

import httpx2
from openai import APITimeoutError, OpenAI
from test_api import json_payload, login_user, test_app

from db import get_db
from errors import ApiError


def ai_response(text="Hello.", *, status="completed"):
    items = [
        {
            "id": "rs_test",
            "type": "reasoning",
            "summary": [],
            "encrypted_content": "opaque-reasoning",
        },
        {
            "id": "msg_test",
            "type": "message",
            "role": "assistant",
            "phase": "final_answer",
            "status": "completed",
            "content": [{"type": "output_text", "text": text, "annotations": []}],
        },
    ]
    models = []
    for item in items:
        model = Mock()
        model.model_dump.return_value = item
        models.append(model)
    return SimpleNamespace(status=status, output_text=text, output=models)


class ChatTests(unittest.TestCase):
    def setUp(self):
        self.upstream = Mock()
        self.upstream.responses.create.return_value = ai_response()
        self.app = test_app(self.upstream)
        self.http = self.app.test_client()
        login = login_user(self.http)
        self.token = login["access_token"]
        self.user_id = login["user"]["id"]
        self.headers = {"Authorization": f"Bearer {self.token}"}
        self.sheet_append = self.enterContext(
            patch(
                "api.chat.append_prompt",
                return_value={"updated_range": "Logs!A2:D2", "updated_rows": 1},
            )
        )

    def post_chat(self, message="Hello", **extra):
        return self.http.post("/chat", headers=self.headers, json={"message": message, **extra})

    def counts(self):
        with self.app.app_context():
            connection = get_db()
            return tuple(
                connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
                for table in ("conversations", "messages")
            )

    def test_authenticated_session_stores_only_token_hash_and_links_the_user(self):
        with self.app.app_context():
            row = get_db().execute("SELECT * FROM api_sessions").fetchone()
            self.assertEqual(
                row["token_hash"], hashlib.sha256(self.token.encode("ascii")).hexdigest()
            )
            self.assertNotEqual(row["token_hash"], self.token)
            self.assertEqual(row["user_id"], self.user_id)

    def test_chat_saves_a_complete_turn(self):
        response = self.post_chat("  How are you?  ")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(json_payload(response)["reply"], "Hello.")
        self.assertEqual(
            [row["role"] for row in json_payload(response)["messages"]], ["user", "assistant"]
        )
        self.assertEqual(json_payload(response)["messages"][0]["content"], "How are you?")
        self.assertNotIn("api_items", json_payload(response)["messages"][1])
        self.assertNotIn("opaque-reasoning", response.get_data(as_text=True))
        self.assertEqual(self.counts(), (1, 2))
        sent = self.upstream.responses.create.call_args.kwargs
        self.assertEqual(sent["model"], "gpt-5.6-sol")
        self.assertEqual(sent["reasoning"], {"effort": "medium"})
        self.assertFalse(sent["store"])
        with self.app.app_context():
            row = get_db().execute("SELECT * FROM conversations").fetchone()
            self.assertEqual(row["user_id"], self.user_id)
            session_id = row["session_id"]
        self.assertTrue(json_payload(response)["sheet"]["saved"])
        self.sheet_append.assert_called_once_with(
            credentials_path="",
            spreadsheet_id="",
            sheet_name="Logs",
            session_id=session_id,
            mode="chat",
            prompt="How are you?",
        )

    def test_sheet_failure_preserves_the_reply_and_chat_history(self):
        self.sheet_append.side_effect = ApiError(
            503, "sheets_unavailable", "Google Sheets is unavailable."
        )
        with patch.object(self.app.logger, "warning"):
            response = self.post_chat()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(json_payload(response)["reply"], "Hello.")
        self.assertFalse(json_payload(response)["sheet"]["saved"])
        self.assertEqual(json_payload(response)["sheet"]["code"], "sheets_unavailable")
        self.assertEqual(self.counts(), (1, 2))

    def test_follow_up_replays_history_and_all_output_items(self):
        first = self.post_chat("My name is Alex.")
        conversation_id = json_payload(first)["conversation_id"]
        response = self.post_chat("What is my name?", conversation_id=conversation_id)
        self.assertEqual(response.status_code, 200)
        history = self.upstream.responses.create.call_args.kwargs["input"]
        self.assertEqual(history[0], {"role": "user", "content": "My name is Alex."})
        self.assertEqual(history[1]["encrypted_content"], "opaque-reasoning")
        self.assertEqual(history[2]["phase"], "final_answer")
        self.assertEqual(history[3], {"role": "user", "content": "What is my name?"})
        self.assertEqual(self.counts(), (1, 4))

    def test_translation_uses_the_same_chat_endpoint(self):
        prompt = "Translate into Traditional Chinese: Tomorrow I will go golfing."
        self.upstream.responses.create.return_value = ai_response(
            "\u660e\u5929\u6211\u8981\u53bb\u6253\u9ad8\u723e\u592b\u3002"
        )
        response = self.post_chat(prompt)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(
            json_payload(response)["reply"],
            "\u660e\u5929\u6211\u8981\u53bb\u6253\u9ad8\u723e\u592b\u3002",
        )
        sent = self.upstream.responses.create.call_args.kwargs
        self.assertEqual(sent["input"][-1]["content"], prompt)
        self.assertIn("translate", sent["instructions"])

    def test_users_cannot_read_change_or_delete_each_others_conversations(self):
        conversation_id = json_payload(self.post_chat())["conversation_id"]
        other = login_user(self.http, google_sub="another-google-user")
        self.assertNotEqual(other["user"]["id"], self.user_id)
        headers = {"Authorization": f"Bearer {other['access_token']}"}
        self.assertEqual(
            json_payload(self.http.get("/conversations", headers=headers)), {"conversations": []}
        )
        for method, path, kwargs in [
            ("get", f"/conversations/{conversation_id}/messages", {}),
            ("delete", f"/conversations/{conversation_id}", {}),
            ("post", "/chat", {"json": {"message": "Hijack", "conversation_id": conversation_id}}),
        ]:
            with self.subTest(method=method):
                response = getattr(self.http, method)(path, headers=headers, **kwargs)
                self.assertEqual(response.status_code, 404)
        self.assertEqual(self.counts(), (1, 2))
        self.assertEqual(self.upstream.responses.create.call_count, 1)
        self.sheet_append.assert_called_once()

    def test_same_user_can_resume_history_after_logout_and_new_login(self):
        first = self.post_chat("My favorite sport is golf.")
        conversation_id = json_payload(first)["conversation_id"]
        response = self.http.post("/auth/logout", headers=self.headers)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(self.post_chat().status_code, 401)

        login = login_user(self.http)
        self.assertEqual(login["user"]["id"], self.user_id)
        self.assertNotEqual(login["access_token"], self.token)
        headers = {"Authorization": f"Bearer {login['access_token']}"}

        listing = self.http.get("/conversations", headers=headers)
        self.assertEqual(listing.status_code, 200)
        self.assertEqual(json_payload(listing)["conversations"][0]["id"], conversation_id)
        history = self.http.get(
            f"/conversations/{conversation_id}/messages", headers=headers
        )
        self.assertEqual(history.status_code, 200)
        self.assertEqual(len(json_payload(history)["messages"]), 2)

        response = self.http.post(
            "/chat",
            headers=headers,
            json={
                "conversation_id": conversation_id,
                "message": "What is my favorite sport?",
            },
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(json_payload(response)["conversation_id"], conversation_id)
        sent_history = self.upstream.responses.create.call_args.kwargs["input"]
        self.assertEqual(
            sent_history[0], {"role": "user", "content": "My favorite sport is golf."}
        )
        self.assertEqual(sent_history[-1]["content"], "What is my favorite sport?")
        self.assertEqual(self.counts(), (1, 4))
        self.assertEqual(
            self.http.delete(f"/conversations/{conversation_id}", headers=headers).status_code,
            200,
        )
        self.assertEqual(self.counts(), (0, 0))

    def test_missing_invalid_and_expired_sessions_are_rejected(self):
        routes = [
            ("post", "/chat", {"json": {"message": "Hello"}}),
            ("get", "/conversations", {}),
            ("get", "/conversations/missing/messages", {}),
            ("delete", "/conversations/missing", {}),
        ]
        for authorization in [None, "Bearer invalid", "Bearer " + "x" * 43]:
            headers = {} if authorization is None else {"Authorization": authorization}
            for method, path, kwargs in routes:
                with self.subTest(authorization=authorization, method=method, path=path):
                    response = getattr(self.http, method)(path, headers=headers, **kwargs)
                    self.assertEqual(response.status_code, 401)
                    self.assertEqual(response.headers["WWW-Authenticate"], "Bearer")
        with self.app.app_context():
            connection = get_db()
            with connection:
                connection.execute("UPDATE api_sessions SET expires_at = 0")
        for method, path, kwargs in routes:
            with self.subTest(expired=True, method=method, path=path):
                response = getattr(self.http, method)(path, headers=self.headers, **kwargs)
                self.assertEqual(response.status_code, 401)
        self.upstream.responses.create.assert_not_called()
        self.sheet_append.assert_not_called()

    def test_invalid_requests_do_not_call_openai_or_write_history(self):
        for payload in [
            None,
            [],
            {},
            {"message": " "},
            {"message": 4},
            {"message": "a" * 4001},
            {"message": "Hello", "conversation_id": ""},
            {"message": "Hello", "conversation_id": 4},
        ]:
            with self.subTest(payload_type=type(payload).__name__):
                response = self.http.post("/chat", headers=self.headers, json=payload)
                self.assertEqual(response.status_code, 400)
        self.upstream.responses.create.assert_not_called()
        self.assertEqual(self.counts(), (0, 0))

    def test_upstream_failure_never_saves_half_a_turn(self):
        self.upstream.responses.create.side_effect = APITimeoutError(
            request=httpx2.Request("POST", "https://api.openai.com/v1/responses")
        )
        self.assertEqual(self.post_chat().status_code, 504)
        self.upstream.responses.create.side_effect = None
        for result in [ai_response(status="incomplete"), ai_response("")]:
            self.upstream.responses.create.return_value = result
            self.assertEqual(self.post_chat().status_code, 502)
        self.assertEqual(self.counts(), (0, 0))

    def test_database_failure_rolls_back_both_messages_and_new_conversation(self):
        with self.app.app_context():
            get_db().executescript("""
                CREATE TRIGGER reject_assistant BEFORE INSERT ON messages
                WHEN NEW.role = 'assistant'
                BEGIN SELECT RAISE(ABORT, 'simulated persistence failure'); END;
            """)
        with patch.object(self.app.logger, "error"):
            response = self.post_chat()
        self.assertEqual(response.status_code, 500)
        self.assertEqual(self.counts(), (0, 0))

    def test_concurrent_change_does_not_append_a_stale_reply(self):
        conversation_id = json_payload(self.post_chat())["conversation_id"]

        def concurrent_response(**_kwargs):
            with closing(sqlite3.connect(self.app.config["DATABASE"])) as connection:
                with connection:
                    connection.execute(
                        "UPDATE conversations SET version = version + 1 WHERE id = ?",
                        (conversation_id,),
                    )
            return ai_response("A stale reply.")

        self.upstream.responses.create.side_effect = concurrent_response
        response = self.post_chat("Follow up", conversation_id=conversation_id)
        self.assertEqual(response.status_code, 409)
        self.assertEqual(self.counts(), (1, 2))

    def test_history_survives_restart_and_delete_cascades_to_messages(self):
        conversation_id = json_payload(self.post_chat("Remember this."))["conversation_id"]
        restarted = test_app(self.upstream, {"DATABASE": self.app.config["DATABASE"]}).test_client()
        response = restarted.get(f"/conversations/{conversation_id}/messages", headers=self.headers)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(json_payload(response)["messages"]), 2)
        listing = restarted.get("/conversations", headers=self.headers)
        self.assertEqual(json_payload(listing)["conversations"][0]["id"], conversation_id)
        self.assertEqual(
            restarted.delete(f"/conversations/{conversation_id}", headers=self.headers).status_code,
            200,
        )
        self.assertEqual(self.counts(), (0, 0))

    def test_session_ttl_must_be_positive(self):
        with self.assertRaisesRegex(ValueError, "SESSION_TTL_SECONDS"):
            test_app(self.upstream, {"SESSION_TTL_SECONDS": 0})

    def test_chat_uses_the_real_sdk_and_preserves_items_on_the_next_request(self):
        calls = []

        def respond(request):
            payload = json.loads(request.content)
            calls.append(payload)
            self.assertEqual(request.url.path, "/v1/responses")
            result = ai_response("Reply from the SDK.")
            return httpx2.Response(
                200,
                json={
                    "id": "resp_test",
                    "object": "response",
                    "created_at": 0,
                    "status": "completed",
                    "model": "gpt-5.6-sol",
                    "output": [item.model_dump() for item in result.output],
                },
            )

        with OpenAI(
            api_key="offline-test-key",
            http_client=httpx2.Client(transport=httpx2.MockTransport(respond)),
            max_retries=0,
        ) as client:
            http = test_app(client).test_client()
            token = login_user(http)["access_token"]
            headers = {"Authorization": f"Bearer {token}"}
            first = http.post("/chat", headers=headers, json={"message": "Hello"})
            self.assertEqual(first.status_code, 200)
            second = http.post(
                "/chat",
                headers=headers,
                json={
                    "message": "Continue",
                    "conversation_id": json_payload(first)["conversation_id"],
                },
            )
        self.assertEqual(second.status_code, 200)
        self.assertEqual(calls[1]["input"][1]["encrypted_content"], "opaque-reasoning")
        self.assertEqual(calls[1]["input"][2]["phase"], "final_answer")
        self.assertFalse(calls[1]["store"])


if __name__ == "__main__":
    unittest.main()
