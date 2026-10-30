"""Test Google identity exchange, app JWT validation, and session revocation."""

import hashlib
import time
import unittest
from unittest.mock import patch

import jwt

from db import get_db
from test_api import json_payload, login_user, test_app


class AuthTests(unittest.TestCase):
    def setUp(self):
        self.app = test_app()
        self.http = self.app.test_client()

    def decode(self, token):
        return jwt.decode(
            token,
            self.app.config["JWT_SECRET_KEY"],
            algorithms=["HS256"],
            issuer=self.app.config["JWT_ISSUER"],
            audience=self.app.config["JWT_AUDIENCE"],
        )

    def assert_unauthorized(self, token):
        response = self.http.get(
            "/auth/me", headers={"Authorization": f"Bearer {token}"}
        )
        self.assertEqual(response.status_code, 401)
        self.assertEqual(json_payload(response)["code"], "unauthorized")
        self.assertEqual(response.headers["WWW-Authenticate"], "Bearer")

    def register_token_hash(self, token, session_id):
        # Keep the database session active so it cannot mask missing JWT verification.
        with self.app.app_context():
            connection = get_db()
            with connection:
                connection.execute(
                    "UPDATE api_sessions SET token_hash = ? WHERE id = ?",
                    (hashlib.sha256(token.encode("ascii")).hexdigest(), session_id),
                )

    def test_google_login_allows_access_to_me(self):
        login = login_user(self.http)
        headers = {
            "Authorization": f"Bearer {login['access_token']}",
        }

        self.assertEqual(login["token_type"], "Bearer")

        response = self.http.get("/auth/me", headers=headers)

        self.assertEqual(response.status_code, 200)
        self.assertEqual(
            json_payload(response)["user"],
            login["user"],
        )
        self.assertEqual(login["user"]["name"], "Test User")

    def test_login_returns_a_signed_jwt_bound_to_the_saved_user_and_session(self):
        login = login_user(self.http)
        token = login["access_token"]
        claims = self.decode(token)
        self.assertEqual(jwt.get_unverified_header(token)["alg"], "HS256")
        self.assertEqual(claims["sub"], login["user"]["id"])
        self.assertEqual(claims["iss"], "voicebridge")
        self.assertEqual(claims["aud"], "voicebridge-app")
        self.assertEqual(claims["exp"] - claims["iat"], login["expires_in"])
        self.assertNotIn("email", claims)

        with self.app.app_context():
            row = get_db().execute("SELECT * FROM api_sessions").fetchone()
            self.assertEqual(row["id"], claims["sid"])
            self.assertEqual(row["user_id"], claims["sub"])
            self.assertEqual(row["expires_at"], claims["exp"])
            self.assertEqual(row["token_hash"], hashlib.sha256(token.encode("ascii")).hexdigest())

    def test_tampered_and_wrong_key_signatures_cannot_access_an_active_session(self):
        login = login_user(self.http)
        claims = self.decode(login["access_token"])
        header, payload, signature = login["access_token"].split(".")
        changed_signature = ("A" if signature[0] != "A" else "B") + signature[1:]
        tokens = {
            "tampered": f"{header}.{payload}.{changed_signature}",
            "wrong-key": jwt.encode(claims, "different-offline-key-" + "b" * 48, algorithm="HS256"),
            "wrong-algorithm": jwt.encode(claims, self.app.config["JWT_SECRET_KEY"], algorithm="HS384"),
            "unsigned": jwt.encode(claims, key=None, algorithm="none"),
        }
        for case, token in tokens.items():
            with self.subTest(case=case):
                self.register_token_hash(token, claims["sid"])
                self.assert_unauthorized(token)

    def test_invalid_claims_are_rejected_even_when_the_signature_and_session_hash_match(self):
        login = login_user(self.http)
        original = self.decode(login["access_token"])
        now = int(time.time())
        cases = {
            "expired": {**original, "iat": now - 3600, "exp": now - 1},
            "future-issued": {**original, "iat": now + 3600},
            "wrong-issuer": {**original, "iss": "another-app"},
            "wrong-audience": {**original, "aud": "another-client"},
            "multiple-audiences": {**original, "aud": ["voicebridge-app", "another-client"]},
            "wrong-user": {**original, "sub": "another-user"},
            "wrong-session": {**original, "sid": "another-session"},
            "empty-user": {**original, "sub": ""},
            "invalid-user-type": {**original, "sub": 123},
            "empty-session": {**original, "sid": ""},
            "invalid-session-type": {**original, "sid": 123},
            "string-expiration": {**original, "exp": str(original["exp"])},
            "fractional-issued-time": {**original, "iat": now - 0.5},
        }
        for claim in ("sub", "sid", "iat", "exp", "iss", "aud"):
            claims = dict(original)
            del claims[claim]
            cases[f"missing-{claim}"] = claims

        for case, claims in cases.items():
            with self.subTest(case=case):
                token = jwt.encode(claims, self.app.config["JWT_SECRET_KEY"], algorithm="HS256")
                self.register_token_hash(token, original["sid"])
                self.assert_unauthorized(token)

    def test_old_opaque_and_malformed_tokens_are_rejected(self):
        login = login_user(self.http)
        claims = self.decode(login["access_token"])
        opaque = "a" * 43
        self.register_token_hash(opaque, claims["sid"])
        for token in (opaque, "a.b.c", "a.b", "a..c", "a.b.c.d", "a.b." + "c" * 4096):
            with self.subTest(length=len(token)):
                self.assert_unauthorized(token)

    def test_signed_jwt_without_an_active_database_session_is_rejected(self):
        login = login_user(self.http)
        with self.app.app_context():
            connection = get_db()
            with connection:
                connection.execute("DELETE FROM api_sessions")
        self.assert_unauthorized(login["access_token"])

    def test_signing_key_change_invalidates_existing_jwts(self):
        login = login_user(self.http)
        self.app.config["JWT_SECRET_KEY"] = "rotated-offline-key-" + "c" * 48
        self.assert_unauthorized(login["access_token"])

    def test_same_google_user_gets_distinct_sessions_and_logout_only_revokes_one(self):
        first = login_user(self.http)
        second = login_user(self.http)
        self.assertEqual(first["user"]["id"], second["user"]["id"])
        self.assertNotEqual(first["access_token"], second["access_token"])
        self.assertNotEqual(self.decode(first["access_token"])["sid"], self.decode(second["access_token"])["sid"])

        headers = {"Authorization": f"Bearer {first['access_token']}"}
        self.assertEqual(self.http.post("/auth/logout", headers=headers).status_code, 200)
        self.assert_unauthorized(first["access_token"])
        response = self.http.get(
            "/auth/me", headers={"Authorization": f"Bearer {second['access_token']}"}
        )
        self.assertEqual(response.status_code, 200)

    def test_google_nonce_is_still_random_short_lived_and_single_use(self):
        response = self.http.post("/auth/google/challenge")
        challenge = json_payload(response)
        self.assertRegex(challenge["nonce"], r"\A[A-Za-z0-9_-]{43}\Z")
        self.assertEqual(challenge["expires_in"], 300)
        claims = {"sub": "nonce-test-user", "nonce": challenge["nonce"]}
        payload = {"id_token": "offline-google-token", "nonce": challenge["nonce"]}
        with patch("services.auth_service.id_token.verify_oauth2_token", return_value=claims) as verifier:
            self.assertEqual(self.http.post("/auth/google", json=payload).status_code, 201)
            replay = self.http.post("/auth/google", json=payload)
            self.assertEqual(replay.status_code, 401)
            self.assertEqual(json_payload(replay)["code"], "google_login_expired")
            verifier.assert_called_once()

        expired = json_payload(self.http.post("/auth/google/challenge"))
        with self.app.app_context():
            connection = get_db()
            with connection:
                connection.execute("UPDATE google_login_challenges SET expires_at = 0")
        with patch("services.auth_service.id_token.verify_oauth2_token") as verifier:
            response = self.http.post(
                "/auth/google", json={"id_token": "offline-google-token", "nonce": expired["nonce"]}
            )
            self.assertEqual(response.status_code, 401)
            verifier.assert_not_called()

    def test_google_nonce_must_match_the_verified_google_id_token(self):
        challenge = json_payload(self.http.post("/auth/google/challenge"))
        with patch(
            "services.auth_service.id_token.verify_oauth2_token",
            return_value={"sub": "nonce-test-user", "nonce": "wrong-nonce"},
        ):
            response = self.http.post(
                "/auth/google", json={"id_token": "offline-google-token", "nonce": challenge["nonce"]}
            )
        self.assertEqual(response.status_code, 401)
        self.assertEqual(json_payload(response)["code"], "invalid_google_token")

    def test_jwt_configuration_requires_a_real_secret_and_nonempty_issuer_and_audience(self):
        for secret in (None, "", "short", "replace_with_a_random_secret_of_at_least_32_bytes"):
            with self.subTest(secret_type=type(secret).__name__):
                with self.assertRaisesRegex(ValueError, "JWT_SECRET_KEY"):
                    test_app(config={"JWT_SECRET_KEY": secret})
        for name in ("JWT_ISSUER", "JWT_AUDIENCE"):
            with self.subTest(setting=name):
                with self.assertRaisesRegex(ValueError, name):
                    test_app(config={name: " "})

    def test_logout_invalidates_the_token(self):
        login = login_user(self.http)
        headers = {
            "Authorization": f"Bearer {login['access_token']}",
        }

        response = self.http.post("/auth/logout", headers=headers)
        self.assertEqual(response.status_code, 200)

        response = self.http.get("/auth/me", headers=headers)
        self.assertEqual(response.status_code, 401)
        self.assertEqual(
            json_payload(response)["code"],
            "unauthorized",
        )

    def test_anonymous_session_creation_is_disabled(self):
        response = self.http.post("/sessions")

        self.assertEqual(response.status_code, 403)
        self.assertEqual(
            json_payload(response)["code"],
            "google_login_required",
        )


if __name__ == "__main__":
    unittest.main()
