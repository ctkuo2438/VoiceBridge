"""Selected Memo ownership, reference snapshots, and continued chat context."""

import json
import unittest
from unittest.mock import Mock, patch

import db
from test_api import json_payload, login_user, test_app
from test_chat import ai_response


class MemoChatTests(unittest.TestCase):
    def setUp(self):
        self.upstream = Mock()
        self.upstream.responses.create.return_value = ai_response("Based on your notes.")
        self.app = test_app(self.upstream)
        self.http = self.app.test_client()
        self.login = login_user(self.http, google_sub="memo-chat-owner")
        self.user_id = self.login["user"]["id"]
        self.headers = {"Authorization": f"Bearer {self.login['access_token']}"}
        self.sheet = self.enterContext(patch("api.chat.append_prompt", return_value={
            "updated_range": "Logs!A2:D2", "updated_rows": 1,
        }))

    def memo(self, prompt="My favorite sport is golf.", user_id=None):
        with self.app.app_context():
            row, _ = db.create_memo(user_id or self.user_id, prompt)
            return row["id"]

    def chat(self, **extra):
        return self.http.post("/chat", headers=self.headers,
            json={"message": "What do my notes say?", **extra})

    def counts(self):
        with self.app.app_context():
            return tuple(db.get_db().execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
                for table in ("conversations", "messages", "conversation_memos"))

    def test_one_or_multiple_selected_memos_reach_the_model_as_user_reference_data(self):
        first = self.memo()
        second = self.memo("My trip is on Friday.")
        for ids in [[first], [second, first]]:
            with self.subTest(count=len(ids)):
                response = self.chat(memo_ids=ids)
                self.assertEqual(response.status_code, 200)
                payload = json_payload(response)
                self.assertEqual([memo["id"] for memo in payload["memo_context"]], ids)
                sent = self.upstream.responses.create.call_args.kwargs
                self.assertEqual(sent["input"][0]["role"], "user")
                self.assertEqual(sent["input"][-1]["content"], "What do my notes say?")
                references = json.loads(sent["input"][0]["content"].split("\n", 1)[1])
                self.assertEqual(references, payload["memo_context"])
                self.assertNotIn("My favorite sport", sent["instructions"])
                self.assertEqual(self.sheet.call_args.kwargs["prompt"], "What do my notes say?")

    def test_history_and_followups_keep_the_saved_snapshot_after_source_changes_and_relogin(self):
        memo_id = self.memo()
        first = json_payload(self.chat(memo_ids=[memo_id]))
        conversation_id = first["conversation_id"]
        with self.app.app_context():
            connection = db.get_db()
            with connection:
                connection.execute("UPDATE memos SET prompt = ? WHERE id = ?", ("Changed source", memo_id))
                connection.execute("DELETE FROM memos WHERE id = ?", (memo_id,))
        self.http.post("/auth/logout", headers=self.headers)
        login = login_user(self.http, google_sub="memo-chat-owner")
        self.headers = {"Authorization": f"Bearer {login['access_token']}"}
        history = self.http.get(f"/conversations/{conversation_id}/messages", headers=self.headers)
        self.assertEqual(json_payload(history)["memo_context"], first["memo_context"])
        response = self.chat(conversation_id=conversation_id, message="What is my favorite sport?")
        self.assertEqual(response.status_code, 200)
        sent = self.upstream.responses.create.call_args.kwargs["input"]
        self.assertIn("My favorite sport is golf.", sent[0]["content"])
        self.assertNotIn("Changed source", sent[0]["content"])
        self.assertEqual(self.counts(), (1, 4, 1))

    def test_other_users_cannot_attach_or_read_the_memos_or_their_chat_snapshots(self):
        memo_id = self.memo()
        conversation_id = json_payload(self.chat(memo_ids=[memo_id]))["conversation_id"]
        other = login_user(self.http, google_sub="other-memo-chat-user")
        headers = {"Authorization": f"Bearer {other['access_token']}"}
        response = self.http.post("/chat", headers=headers,
            json={"message": "Read a private memo", "memo_ids": [memo_id]})
        self.assertEqual(response.status_code, 404)
        self.assertEqual(json_payload(response)["code"], "memo_not_found")
        self.assertEqual(self.http.get(f"/conversations/{conversation_id}/messages", headers=headers).status_code, 404)
        self.assertEqual(self.upstream.responses.create.call_count, 1)

    def test_invalid_or_oversized_selection_is_rejected_before_calling_the_model(self):
        memo_id = self.memo()
        for ids in ["not-a-list", [memo_id, memo_id], [None], [""], [7], ["x" * 129], [str(i) for i in range(11)]]:
            with self.subTest(ids=ids):
                self.assertEqual(self.chat(memo_ids=ids).status_code, 400)
        oversized = self.memo("x" * 20_001)
        self.assertEqual(self.chat(memo_ids=[oversized]).status_code, 400)
        self.assertEqual(self.chat(memo_ids=["missing"]).status_code, 404)
        self.upstream.responses.create.assert_not_called()
        self.sheet.assert_not_called()
        self.assertEqual(self.counts(), (0, 0, 0))

    def test_memos_can_only_be_attached_when_starting_a_new_conversation(self):
        memo_id = self.memo()
        conversation_id = json_payload(self.chat())["conversation_id"]
        response = self.chat(conversation_id=conversation_id, memo_ids=[memo_id])
        self.assertEqual(response.status_code, 400)
        self.assertEqual(self.counts(), (1, 2, 0))
        self.assertEqual(self.upstream.responses.create.call_count, 1)

    def test_snapshots_and_messages_are_committed_together_and_deleted_with_the_chat(self):
        memo_id = self.memo()
        with self.app.app_context():
            db.get_db().executescript("""
                CREATE TRIGGER reject_memo_snapshot BEFORE INSERT ON conversation_memos
                BEGIN SELECT RAISE(ABORT, 'simulated snapshot failure'); END;
            """)
        with patch.object(self.app.logger, "error"):
            self.assertEqual(self.chat(memo_ids=[memo_id]).status_code, 500)
        self.assertEqual(self.counts(), (0, 0, 0))
        self.sheet.assert_not_called()
        with self.app.app_context():
            db.get_db().execute("DROP TRIGGER reject_memo_snapshot")
        response = self.chat(memo_ids=[memo_id])
        conversation_id = json_payload(response)["conversation_id"]
        self.assertEqual(self.counts(), (1, 2, 1))
        self.http.delete(f"/conversations/{conversation_id}", headers=self.headers)
        self.assertEqual(self.counts(), (0, 0, 0))


if __name__ == "__main__":
    unittest.main()
