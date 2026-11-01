"""Memo persistence, ownership, retry safety, and independent Sheet copy status."""

import sqlite3
import contextlib
import io
import os
import threading
from contextlib import closing
import unittest
from unittest.mock import patch
from uuid import uuid4

import db
from errors import ApiError
from test_api import json_payload, login_user, test_app
from scripts import memo_client
from werkzeug.serving import WSGIRequestHandler, make_server


class MemoApiTests(unittest.TestCase):
    def setUp(self):
        self.app = test_app()
        self.http = self.app.test_client()
        self.login = login_user(self.http, google_sub="memo-owner")
        self.headers = {"Authorization": f"Bearer {self.login['access_token']}"}
        self.append = patch(
            "services.memo_service.append_prompt",
            return_value={"timestamp": "2026-10-28T12:00:00+00:00", "updated_range": "Logs!A2:D2", "updated_rows": 1},
        ).start()
        self.addCleanup(patch.stopall)

    def save(self, prompt="My memo", *, request_id=None, headers=None, title=None):
        payload = {"prompt": prompt}
        if title is not None:
            payload["title"] = title
        if request_id is not None:
            payload["request_id"] = request_id
        return self.http.post("/memos", json=payload, headers=self.headers if headers is None else headers)

    def history(self, *, http=None, headers=None):
        response = (http or self.http).get("/memos", headers=self.headers if headers is None else headers)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers["Cache-Control"], "no-store")
        return json_payload(response)["memos"]

    def update(self, memo, *, title="Revised title", prompt="Revised note", version=None, headers=None):
        return self.http.put(f"/memos/{memo['id']}", headers=self.headers if headers is None else headers,
            json={"title": title, "prompt": prompt, "version": memo["version"] if version is None else version})

    def test_titles_are_trimmed_or_derived_and_retry_identity_includes_the_title(self):
        memo = json_payload(self.save("First line\nSecond line", title="  Project notes  "))["memo"]
        self.assertEqual(memo["title"], "Project notes")
        self.assertEqual(memo["updated_at"], memo["created_at"])
        self.assertEqual(memo["version"], 0)
        generated = json_payload(self.save("  課後回顧 🌱\nMore details  "))["memo"]
        self.assertEqual(generated["title"], "課後回顧 🌱")
        self.assertEqual(len(json_payload(self.save("🌱" * 100))["memo"]["title"]), 80)
        request_id = str(uuid4())
        self.save(request_id=request_id, title="Original title")
        self.assertEqual(self.save(request_id=request_id, title="Different title").status_code, 409)

    def test_edit_updates_the_same_memo_and_an_explicit_retry_does_not_increment_again(self):
        memo = json_payload(self.save())["memo"]
        response = self.update(memo, title="  New title  ", prompt="  Revised note  ")
        self.assertEqual(response.status_code, 200)
        updated = json_payload(response)["memo"]
        self.assertEqual(updated["id"], memo["id"])
        self.assertEqual(updated["title"], "New title")
        self.assertEqual(updated["prompt"], "Revised note")
        self.assertEqual(updated["created_at"], memo["created_at"])
        self.assertEqual(updated["version"], 1)
        self.assertEqual(updated["sheet_status"], "unconfirmed")
        self.assertTrue(updated["sheet_outdated"])
        repeated = self.update(memo, title="New title", prompt="Revised note")
        self.assertEqual(repeated.status_code, 200)
        self.assertEqual(json_payload(repeated)["memo"], updated)
        self.assertEqual(self.history(), [updated])
        self.append.assert_called_once()

    def test_a_stale_edit_cannot_overwrite_newer_content(self):
        memo = json_payload(self.save())["memo"]
        updated = json_payload(self.update(memo))["memo"]
        response = self.update(memo, prompt="A stale overwrite")
        self.assertEqual(response.status_code, 409)
        self.assertEqual(json_payload(response)["code"], "memo_changed")
        self.assertEqual(self.history(), [updated])
        title_only = json_payload(self.update(updated, title="Another title", prompt=updated["prompt"]))["memo"]
        self.assertEqual(title_only["version"], 2)
        self.assertEqual(self.update(memo).status_code, 409)

    def test_title_only_edit_keeps_the_existing_sheet_copy_status(self):
        memo = json_payload(self.save())["memo"]
        response = self.update(memo, title="Renamed", prompt=memo["prompt"])
        self.assertEqual(json_payload(response)["memo"]["sheet_status"], "saved")
        self.append.assert_called_once()

    def test_invalid_titles_and_edits_are_rejected_without_writing(self):
        for title in [7, [], "x" * 121]:
            self.assertEqual(self.save(title=title).status_code, 400)
        memo = json_payload(self.save(title="🌱" * 120))["memo"]
        for patch_fields in [{"title": " "}, {"title": "x" * 121}, {"prompt": " "},
                             {"version": -1}, {"version": True}, {"version": "0"}, {"version": None}, {"version": 2 ** 80}]:
            data = {"title": "Changed", "prompt": "Changed note", "version": 0, **patch_fields}
            self.assertEqual(self.http.put(f"/memos/{memo['id']}", json=data, headers=self.headers).status_code, 400)
        self.assertEqual(self.history(), [memo])

    def test_edits_require_ownership_and_an_active_login(self):
        memo = json_payload(self.save())["memo"]
        other = login_user(self.http, google_sub="other-editor")
        other_headers = {"Authorization": f"Bearer {other['access_token']}"}
        self.assertEqual(self.update(memo, headers=other_headers).status_code, 404)
        self.assertEqual(self.update({**memo, "id": "missing"}).status_code, 404)
        self.assertEqual(self.update(memo, headers={}).status_code, 401)
        self.assertEqual(self.history(), [memo])

    def test_a_late_sheet_result_cannot_mark_changed_content_as_synced(self):
        def edit_while_copying(**_kwargs):
            with self.app.app_context():
                row = db.get_db().execute("SELECT * FROM memos").fetchone()
                db.update_memo(self.login["user"]["id"], row["id"], title="Edited while copying",
                    prompt="New content", version=0)
            return {"updated_range": "Logs!A2:D2", "updated_rows": 1}
        self.append.side_effect = edit_while_copying
        response = self.save()
        self.assertEqual(response.status_code, 201)
        self.assertEqual(json_payload(response)["memo"]["sheet_status"], "unconfirmed")
        self.assertTrue(json_payload(response)["memo"]["sheet_outdated"])
        self.assertFalse(json_payload(response)["sheet"]["saved"])

    def test_legacy_database_is_upgraded_in_place_and_the_migration_is_repeatable(self):
        request_id = str(uuid4())
        with self.app.app_context():
            connection = db.get_db()
            connection.execute("DROP TABLE memos")
            connection.executescript("""CREATE TABLE memos (
                id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), request_id TEXT,
                prompt TEXT NOT NULL, created_at TEXT NOT NULL, sheet_status TEXT NOT NULL DEFAULT 'pending',
                sheet_result TEXT, UNIQUE(user_id, request_id));""")
            with connection:
                connection.execute("INSERT INTO memos VALUES (?, ?, ?, ?, ?, ?, ?)",
                    ("legacy-note", self.login["user"]["id"], request_id, "原本的筆記\nDetails", "2026-10-01T10:00:00Z", "saved", '{"saved":true}'))
        restarted = test_app(config={"DATABASE": self.app.config["DATABASE"]})
        memos = self.history(http=restarted.test_client())
        self.assertEqual(len(memos), 1)
        self.assertEqual(memos[0]["id"], "legacy-note")
        self.assertEqual(memos[0]["title"], "原本的筆記")
        self.assertEqual(memos[0]["prompt"], "原本的筆記\nDetails")
        self.assertEqual(memos[0]["created_at"], memos[0]["updated_at"])
        self.assertEqual(memos[0]["version"], 0)
        with restarted.app_context():
            self.assertEqual(db.get_db().execute("SELECT request_id FROM memos").fetchone()[0], request_id)
        again = test_app(config={"DATABASE": self.app.config["DATABASE"]}).test_client()
        self.assertEqual(self.history(http=again), memos)

    def test_authenticated_notes_are_trimmed_owned_and_listed_newest_first(self):
        self.assertEqual(self.history(), [])
        with patch("db.utc_now", return_value="2026-10-28T12:00:00.000Z"):
            first = self.save("  First memo  ")
            second = self.save("Second memo")
        self.assertEqual(first.status_code, 201)
        payload = json_payload(first)
        self.assertTrue(payload["saved"])
        self.assertTrue(payload["sheet"]["saved"])
        self.assertEqual(payload["memo"]["prompt"], "First memo")
        self.assertEqual(payload["memo"]["sheet_status"], "saved")
        self.assertEqual([row["prompt"] for row in self.history()], ["Second memo", "First memo"])
        with self.app.app_context():
            rows = db.get_db().execute("SELECT user_id FROM memos").fetchall()
        self.assertEqual([row["user_id"] for row in rows], [self.login["user"]["id"]] * 2)
        self.assertNotEqual(json_payload(second)["memo"]["id"], payload["memo"]["id"])
        self.assertEqual(self.append.call_args_list[0].kwargs["prompt"], "First memo")
        self.assertEqual(self.append.call_args_list[0].kwargs["mode"], "memo")

    def test_history_survives_logout_relogin_and_app_restart_for_the_same_google_user(self):
        saved = json_payload(self.save())["memo"]
        self.http.post("/auth/logout", headers=self.headers)
        self.assertEqual(self.http.get("/memos", headers=self.headers).status_code, 401)
        login = login_user(self.http, google_sub="memo-owner")
        self.assertEqual(login["user"]["id"], self.login["user"]["id"])
        self.assertNotEqual(login["access_token"], self.login["access_token"])
        new_headers = {"Authorization": f"Bearer {login['access_token']}"}
        self.assertEqual(self.history(headers=new_headers), [saved])
        restarted = test_app(config={"DATABASE": self.app.config["DATABASE"]}).test_client()
        self.assertEqual(self.history(http=restarted, headers=new_headers), [saved])

    def test_users_cannot_read_or_assign_each_others_memos(self):
        owner_memo = json_payload(self.save())["memo"]
        other = login_user(self.http, google_sub="another-memo-user")
        headers = {"Authorization": f"Bearer {other['access_token']}"}
        self.assertEqual(self.history(headers=headers), [])
        response = self.http.post("/memos", headers=headers, json={
            "prompt": "Other user's note", "user_id": self.login["user"]["id"],
        })
        self.assertEqual(response.status_code, 201)
        forged = self.http.get(f"/memos?user_id={self.login['user']['id']}", headers=headers)
        self.assertEqual([row["prompt"] for row in json_payload(forged)["memos"]], ["Other user's note"])
        self.assertEqual(self.history(), [owner_memo])

    def test_sheet_failure_keeps_the_committed_memo_and_reports_its_copy_status(self):
        def fail_after_commit(**_kwargs):
            with closing(sqlite3.connect(self.app.config["DATABASE"])) as connection:
                self.assertEqual(connection.execute("SELECT COUNT(*) FROM memos").fetchone()[0], 1)
            raise ApiError(504, "sheets_timeout", "Google Sheets timed out.")

        self.append.side_effect = fail_after_commit
        response = self.save("Keep my note")
        self.assertEqual(response.status_code, 201)
        payload = json_payload(response)
        self.assertTrue(payload["saved"])
        self.assertFalse(payload["sheet"]["saved"])
        self.assertEqual(payload["memo"]["sheet_status"], "unconfirmed")
        self.assertEqual(self.history(), [payload["memo"]])

    def test_an_unexpected_sheet_error_does_not_lose_the_note_or_expose_details(self):
        self.append.side_effect = RuntimeError("private-service-account-detail")
        response = self.save()
        self.assertEqual(response.status_code, 201)
        self.assertTrue(json_payload(response)["saved"])
        self.assertNotIn("private-service-account-detail", response.get_data(as_text=True))
        self.assertEqual(len(self.history()), 1)

    def test_retries_reuse_one_memo_and_one_sheet_attempt_even_after_relogin(self):
        request_id = str(uuid4())
        first = self.save(request_id=request_id)
        login = login_user(self.http, google_sub="memo-owner")
        headers = {"Authorization": f"Bearer {login['access_token']}"}
        repeated = self.save(request_id=request_id, headers=headers)
        self.assertEqual(repeated.status_code, 200)
        self.assertEqual(json_payload(first), json_payload(repeated))
        self.assertEqual(len(self.history()), 1)
        self.append.assert_called_once()
        conflict = self.save("Different text", request_id=request_id)
        self.assertEqual(conflict.status_code, 409)
        self.assertEqual(len(self.history()), 1)

    def test_request_ids_are_scoped_to_the_user_and_pending_copies_are_not_appended_twice(self):
        request_id = str(uuid4())
        with self.app.app_context():
            row, _ = db.create_memo(self.login["user"]["id"], "Still being copied", request_id)
            memo_id = row["id"]
        repeated = self.save("Still being copied", request_id=request_id)
        self.assertEqual(repeated.status_code, 200)
        self.assertEqual(json_payload(repeated)["memo"]["id"], memo_id)
        self.assertEqual(json_payload(repeated)["memo"]["sheet_status"], "pending")
        self.append.assert_not_called()
        other = login_user(self.http, google_sub="other-user")
        response = self.save("Different owner's note", request_id=request_id,
            headers={"Authorization": f"Bearer {other['access_token']}"})
        self.assertEqual(response.status_code, 201)
        self.append.assert_called_once()

    def test_invalid_requests_and_missing_or_revoked_tokens_cannot_write(self):
        for payload in [{}, {"prompt": " "}, {"prompt": 7},
                        {"prompt": "Note", "request_id": "bad"}, {"prompt": "Note", "request_id": []}]:
            with self.subTest(payload=payload):
                response = self.http.post("/memos", json=payload, headers=self.headers)
                self.assertEqual(response.status_code, 400)
        for method in ["GET", "POST"]:
            response = self.http.open("/memos", method=method, json={"prompt": "No token"})
            self.assertEqual(response.status_code, 401)
        self.http.post("/auth/logout", headers=self.headers)
        self.assertEqual(self.save().status_code, 401)
        self.append.assert_not_called()

    def test_python_memo_client_uses_the_new_saved_memo_and_sheet_contract(self):
        class QuietRequestHandler(WSGIRequestHandler):
            def log(self, *_args, **_kwargs):
                pass

        server = make_server("127.0.0.1", 0, self.app, request_handler=QuietRequestHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        output = io.StringIO()
        try:
            with (
                patch.dict(os.environ, {}, clear=True),
                patch("scripts.http_client.getpass", return_value=self.login["access_token"]),
                patch("scripts.memo_client.API_URL", f"http://127.0.0.1:{server.server_port}"),
                patch("scripts.memo_client.record_microphone", return_value=("mic.wav", b"test-audio", "audio/wav")),
                patch("scripts.memo_client.transcribe_via_api", return_value="Client memo"),
                patch("scripts.memo_client.input", side_effect=["", "y"]),
                contextlib.redirect_stdout(output),
            ):
                self.assertEqual(memo_client.main(), 0)
            self.assertIn("Saved memo:", output.getvalue())
            self.assertIn("Saved to Google Sheet:", output.getvalue())
            self.assertEqual(self.history()[0]["prompt"], "Client memo")
        finally:
            server.shutdown()
            thread.join(timeout=5)
            server.server_close()


if __name__ == "__main__":
    unittest.main()
