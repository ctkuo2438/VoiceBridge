"""Selected Memo ownership, reference snapshots, and continued chat context."""

import json
import unittest
from unittest.mock import Mock, patch

import httpx2
from openai import OpenAI

import db
from services.memo_grounding import ACTION_INSTRUCTIONS, reference_material
from test_api import json_payload, login_user, test_app
from test_chat import ai_response


class MemoChatTests(unittest.TestCase):
    def setUp(self):
        self.upstream = Mock()
        self.upstream.responses.create.side_effect = self.grounded_response
        self.app = test_app(self.upstream)
        self.http = self.app.test_client()
        self.login = login_user(self.http, google_sub="memo-chat-owner")
        self.user_id = self.login["user"]["id"]
        self.headers = {"Authorization": f"Bearer {self.login['access_token']}"}
        self.sheet = self.enterContext(patch("api.chat.append_prompt", return_value={
            "updated_range": "Logs!A2:D2", "updated_rows": 1,
        }))

    def grounded_response(self, **kwargs):
        if "text" not in kwargs:
            return ai_response("Based on your notes.")
        references = json.loads(kwargs["input"][0]["content"].split("\n", 1)[1])
        return ai_response(json.dumps({"sections": [{"text": "Based on your notes.", "kind": "note",
            "sources": [{"memo_id": references[0]["id"], "paragraph_id": "p1"}]}]}))

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
                self.assertEqual([memo["id"] for memo in references], ids)
                self.assertEqual(references[0]["paragraphs"][0]["text"], payload["memo_context"][0]["prompt"])
                self.assertEqual(references[0]["title"], payload["memo_context"][0]["title"])
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

    def test_editing_a_memo_uses_new_content_in_new_chats_and_keeps_existing_chat_snapshots(self):
        memo_id = self.memo("Original reference")
        existing = json_payload(self.chat(memo_ids=[memo_id]))
        response = self.http.put(f"/memos/{memo_id}", headers=self.headers,
            json={"title": "Updated note", "prompt": "Revised reference", "version": 0})
        self.assertEqual(response.status_code, 200)
        old = self.http.get(f"/conversations/{existing['conversation_id']}/messages", headers=self.headers)
        self.assertEqual(json_payload(old)["memo_context"][0]["prompt"], "Original reference")
        new = json_payload(self.chat(memo_ids=[memo_id]))
        self.assertEqual(new["memo_context"][0]["prompt"], "Revised reference")

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

    def test_note_actions_use_a_strict_reply_schema_and_meaningful_conversation_titles(self):
        memo_id = self.memo("Project discussion\n\nReview the report on Friday.")
        for action in ("summary", "todos", "bilingual", "ask"):
            response = self.chat(memo_ids=[memo_id], memo_action=action)
            self.assertEqual(response.status_code, 200)
            sent = self.upstream.responses.create.call_args.kwargs
            self.assertEqual(sent["text"]["format"]["type"], "json_schema")
            self.assertTrue(sent["text"]["format"]["strict"])
            self.assertIn(ACTION_INSTRUCTIONS[action], sent["instructions"])
            self.assertFalse(sent["store"])
            conversation_id = json_payload(response)["conversation_id"]
            titles = json_payload(self.http.get("/conversations", headers=self.headers))["conversations"]
            self.assertIn("Project discussion", next(row["title"] for row in titles if row["id"] == conversation_id))

    def test_invalid_actions_are_rejected_before_any_model_call(self):
        memo_id = self.memo()
        for action in ("unknown", [], 5, ""):
            self.assertEqual(self.chat(memo_ids=[memo_id], memo_action=action).status_code, 400)
        self.assertEqual(self.chat(memo_action="summary").status_code, 400)
        self.upstream.responses.create.assert_not_called()
        conversation_id = json_payload(self.chat(memo_ids=[memo_id]))["conversation_id"]
        calls = self.upstream.responses.create.call_count
        self.assertEqual(self.chat(conversation_id=conversation_id, memo_action="summary").status_code, 400)
        self.assertEqual(self.upstream.responses.create.call_count, calls)

    def test_citations_are_exact_saved_paragraphs_and_survive_source_edits_and_relogin(self):
        memo_id = self.memo("Project discussion\n\nReview the report on Friday.")
        self.upstream.responses.create.side_effect = lambda **_: ai_response(json.dumps({"sections": [{
            "text": "Review the report on Friday.", "kind": "note",
            "sources": [{"memo_id": memo_id, "paragraph_id": "p2"}],
        }]}))
        first = json_payload(self.chat(memo_ids=[memo_id], memo_action="todos"))
        section = first["messages"][1]["sections"][0]
        self.assertEqual(first["reply"], section["text"])
        self.assertEqual(section["sources"][0], {"memo_id": memo_id, "memo_title": "Project discussion",
            "paragraph_id": "p2", "quote": "Review the report on Friday."})
        self.http.put(f"/memos/{memo_id}", headers=self.headers,
            json={"title": "Changed title", "prompt": "Different content", "version": 0})
        self.http.post("/auth/logout", headers=self.headers)
        login = login_user(self.http, google_sub="memo-chat-owner")
        self.headers = {"Authorization": f"Bearer {login['access_token']}"}
        saved = json_payload(self.http.get(f"/conversations/{first['conversation_id']}/messages", headers=self.headers))
        self.assertEqual(saved["messages"][1]["sections"][0], section)
        self.assertEqual(saved["memo_context"][0]["title"], "Project discussion")
        followup = self.chat(conversation_id=first["conversation_id"], message="What is due?")
        self.assertEqual(followup.status_code, 200)
        self.assertEqual(json_payload(followup)["messages"][1]["sections"][0]["sources"], section["sources"])

    def test_invalid_or_invented_citations_never_save_a_turn_or_log_to_sheets(self):
        memo_id, unrelated = self.memo(), self.memo("Not selected private note")
        invalid_sections = [
            {"text": "Claim", "kind": "note", "sources": [{"memo_id": unrelated, "paragraph_id": "p1"}]},
            {"text": "Claim", "kind": "note", "sources": [{"memo_id": memo_id, "paragraph_id": "p999"}]},
            {"text": "Claim", "kind": "note", "sources": []},
            {"text": "Claim", "kind": "note", "sources": [{"memo_id": memo_id, "paragraph_id": "p1", "quote": "Invented"}]},
        ]
        for section in invalid_sections:
            self.upstream.responses.create.side_effect = lambda section=section, **_: ai_response(json.dumps({"sections": [section]}))
            response = self.chat(memo_ids=[memo_id])
            self.assertEqual(response.status_code, 502)
            self.assertEqual(self.counts(), (0, 0, 0))
        self.sheet.assert_not_called()

    def test_missing_information_and_general_suggestions_are_labeled_without_fake_sources(self):
        memo_id = self.memo()
        for kind in ("not_found", "general"):
            self.upstream.responses.create.side_effect = lambda kind=kind, **_: ai_response(json.dumps({"sections": [{
                "text": "The note does not state a deadline." if kind == "not_found" else "Consider making a checklist.",
                "kind": kind, "sources": [],
            }]}))
            response = self.chat(memo_ids=[memo_id])
            self.assertEqual(response.status_code, 200)
            section = json_payload(response)["messages"][1]["sections"][0]
            self.assertEqual(section["kind"], kind)
            self.assertEqual(section["sources"], [])

    def test_malformed_and_incomplete_note_answers_are_not_saved(self):
        memo_id = self.memo()
        for response in (ai_response("Unstructured answer"), ai_response('{"sections":[] }'),
                         ai_response('{"sections":[]}', status="incomplete")):
            self.upstream.responses.create.side_effect = None
            self.upstream.responses.create.return_value = response
            self.assertEqual(self.chat(memo_ids=[memo_id]).status_code, 502)
            self.assertEqual(self.counts(), (0, 0, 0))
        self.sheet.assert_not_called()

    def test_a_model_refusal_is_readable_without_invented_sources(self):
        memo_id = self.memo()
        refusal = ai_response("")
        refusal.output[-1].model_dump.return_value["content"] = [
            {"type": "refusal", "refusal": "I cannot help with that request."}]
        self.upstream.responses.create.side_effect = None
        self.upstream.responses.create.return_value = refusal
        response = self.chat(memo_ids=[memo_id])
        self.assertEqual(response.status_code, 200)
        payload = json_payload(response)
        self.assertEqual(payload["reply"], "I cannot help with that request.")
        self.assertNotIn("sections", payload["messages"][1])

    def test_structured_note_answers_use_the_real_sdk_and_keep_followup_history(self):
        memo_id = self.memo("Review the report on Friday.")
        calls = []

        def respond(request):
            body = json.loads(request.content)
            calls.append(body)
            references = json.loads(body["input"][0]["content"].split("\n", 1)[1])
            output = ai_response(json.dumps({"sections": [{"text": "Review the report on Friday.",
                "kind": "note", "sources": [{"memo_id": references[0]["id"], "paragraph_id": "p1"}]}]}))
            return httpx2.Response(200, json={"id": "resp_memo", "object": "response", "created_at": 0,
                "status": "completed", "model": "gpt-5.6-sol",
                "output": [item.model_dump() for item in output.output]})

        with OpenAI(api_key="offline-test-key", max_retries=0,
                    http_client=httpx2.Client(transport=httpx2.MockTransport(respond))) as client:
            self.app.extensions["openai_client"] = client
            first = self.chat(memo_ids=[memo_id], memo_action="summary")
            self.assertEqual(first.status_code, 200)
            followup = self.chat(conversation_id=json_payload(first)["conversation_id"])
        self.assertEqual(followup.status_code, 200)
        self.assertEqual(calls[0]["text"]["format"]["type"], "json_schema")
        self.assertTrue(calls[0]["text"]["format"]["strict"])
        self.assertEqual(calls[1]["input"][2]["encrypted_content"], "opaque-reasoning")
        self.assertEqual(calls[1]["input"][3]["phase"], "final_answer")
        self.assertFalse(calls[1]["store"])

    def test_legacy_messages_and_reference_titles_upgrade_without_using_edited_notes(self):
        memo_id = self.memo("Original snapshot title\n\nOriginal paragraph.")
        first = json_payload(self.chat(memo_ids=[memo_id]))
        self.http.put(f"/memos/{memo_id}", headers=self.headers,
            json={"title": "New live title", "prompt": "New live contents", "version": 0})
        with self.app.app_context():
            connection = db.get_db()
            connection.execute("ALTER TABLE messages DROP COLUMN sections_json")
            connection.execute("ALTER TABLE conversation_memos DROP COLUMN title")
        expected = [{key: value for key, value in message.items() if key != "sections"}
                    for message in first["messages"]]
        for _ in range(2):
            restarted = test_app(config={"DATABASE": self.app.config["DATABASE"]}).test_client()
            loaded = restarted.get(f"/conversations/{first['conversation_id']}/messages", headers=self.headers)
            self.assertEqual(loaded.status_code, 200)
            payload = json_payload(loaded)
            self.assertEqual(payload["messages"], expected)
            self.assertEqual(payload["memo_context"][0]["title"], "Original snapshot title")
            self.assertEqual(payload["memo_context"][0]["prompt"], "Original snapshot title\n\nOriginal paragraph.")

    def test_paragraphs_keep_exact_source_substrings_and_bound_long_excerpts(self):
        prompt = "  First paragraph.\n\n第二段 🌱\n\n" + "x" * 2100
        refs = reference_material([{"id": "m", "title": "Title", "prompt": prompt}])[0]["paragraphs"]
        self.assertEqual([p["id"] for p in refs], ["p1", "p2", "p3", "p4", "p5"])
        self.assertEqual(refs[1]["text"], "第二段 🌱")
        self.assertTrue(all(p["text"] in prompt and 0 < len(p["text"]) <= 1000 for p in refs))


if __name__ == "__main__":
    unittest.main()
