"""Test Google sign-in, logout, and disabled anonymous sessions."""

import unittest

from test_api import json_payload, login_user, test_app


class AuthTests(unittest.TestCase):
    def setUp(self):
        self.app = test_app()
        self.http = self.app.test_client()

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
