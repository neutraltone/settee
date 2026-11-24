"""Tests for tools/keys/check_keys.py, as release.yml runs it.

The services' answers are canned, handed to the script in place of its HTTP call, so these make
no network calls and need no key. The keys are made up and in the right format.
"""

import io
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_keys  # noqa: E402

OPENSUBTITLES = "Ab3dEf6hIj9kLm2nOp5qRs8tUv1wXy4z"
TMDB_V3 = "0123456789abcdef0123456789abcdef"
TMDB_V4 = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJ4In0.c2lnbmF0dXJlLV9zaWc"
ENV = {"OPENSUBTITLES_API_KEY": OPENSUBTITLES, "TMDB_API_KEY": TMDB_V3}

TMDB_OK = (200, '{"images":{"base_url":"http://image.tmdb.org/t/p/"}}')
TMDB_INVALID = (
    401,
    '{"status_code":7,"status_message":"Invalid API key: You must be granted a valid key.","success":false}',
)
OPENSUBTITLES_OK = (401, '{"message":"invalid username/password","status":401}')
OPENSUBTITLES_INVALID = (403, '{"message":"You cannot consume this service"}')
CLOUDFLARE = (
    403,
    "<!DOCTYPE html><html><head><title>Just a moment...</title></head>"
    "<body>Checking your browser. You cannot consume this service</body></html>",
)


class Answers:
    """Stands in for the HTTP call: the canned answer for each host, and the requests it was asked."""

    def __init__(self, tmdb=TMDB_OK, opensubtitles=OPENSUBTITLES_OK):
        self.answers = {"api.themoviedb.org": tmdb, "api.opensubtitles.com": opensubtitles}
        self.requests = []

    def __call__(self, request):
        self.requests.append(request)
        answer = self.answers[request.host]
        if isinstance(answer, BaseException):
            raise answer
        return answer


def run(answers, env=ENV):
    out = io.StringIO()
    code = check_keys.main(env, answers, out)
    return code, out.getvalue()


class CheckKeysTest(unittest.TestCase):
    def assertNoKeys(self, output):
        for key in (OPENSUBTITLES, TMDB_V3, TMDB_V4):
            self.assertNotIn(key, output)

    def test_working_keys_pass(self):
        code, out = run(Answers())
        self.assertEqual(code, 0)
        self.assertNotIn("::error::", out)
        self.assertNotIn("::warning::", out)
        self.assertNoKeys(out)

    def test_tmdb_turning_the_key_down_fails_the_release(self):
        code, out = run(Answers(tmdb=TMDB_INVALID))
        self.assertEqual(code, 1)
        self.assertIn("::error::", out)
        self.assertIn("TMDB_API_KEY", out)
        self.assertNoKeys(out)

    def test_opensubtitles_turning_the_key_down_fails_the_release(self):
        code, out = run(Answers(opensubtitles=OPENSUBTITLES_INVALID))
        self.assertEqual(code, 1)
        self.assertIn("::error::", out)
        self.assertIn("OPENSUBTITLES_API_KEY", out)
        self.assertNoKeys(out)

    def test_a_cloudflare_page_is_only_a_warning(self):
        code, out = run(Answers(opensubtitles=CLOUDFLARE, tmdb=(403, CLOUDFLARE[1])))
        self.assertEqual(code, 0)
        self.assertNotIn("::error::", out)
        self.assertEqual(out.count("::warning::"), 2)

    def test_a_timeout_is_only_a_warning(self):
        code, out = run(Answers(tmdb=TimeoutError("timed out"), opensubtitles=TimeoutError("timed out")))
        self.assertEqual(code, 0)
        self.assertEqual(out.count("::warning::"), 2)

    def test_a_5xx_or_429_is_only_a_warning(self):
        code, out = run(Answers(tmdb=(503, "Service Unavailable"), opensubtitles=(429, '{"message":"Throttle"}')))
        self.assertEqual(code, 0)
        self.assertEqual(out.count("::warning::"), 2)

    def test_a_401_from_tmdb_with_another_status_code_is_only_a_warning(self):
        code, out = run(Answers(tmdb=(401, '{"status_code":3,"status_message":"Authentication failed"}')))
        self.assertEqual(code, 0)
        self.assertIn("::warning::", out)

    def test_both_turned_down_are_both_reported(self):
        code, out = run(Answers(tmdb=TMDB_INVALID, opensubtitles=OPENSUBTITLES_INVALID))
        self.assertEqual(code, 1)
        self.assertEqual(out.count("::error::"), 2)

    def test_a_v3_tmdb_key_is_sent_as_api_key_and_a_v4_token_as_a_bearer(self):
        answers = Answers()
        run(answers, {**ENV, "TMDB_API_KEY": TMDB_V3})
        tmdb = next(r for r in answers.requests if r.host == "api.themoviedb.org")
        self.assertIn("api_key=" + TMDB_V3, tmdb.full_url)
        self.assertIsNone(tmdb.get_header("Authorization"))

        answers = Answers()
        run(answers, {**ENV, "TMDB_API_KEY": TMDB_V4})
        tmdb = next(r for r in answers.requests if r.host == "api.themoviedb.org")
        self.assertNotIn(TMDB_V4, tmdb.full_url)
        self.assertEqual(tmdb.get_header("Authorization"), "Bearer " + TMDB_V4)

    def test_opensubtitles_is_asked_to_log_in_with_placeholders_and_the_key(self):
        answers = Answers()
        run(answers)
        login = next(r for r in answers.requests if r.host == "api.opensubtitles.com")
        self.assertEqual(login.get_method(), "POST")
        self.assertTrue(login.full_url.endswith("/api/v1/login"))
        self.assertEqual(login.get_header("Api-key"), OPENSUBTITLES)
        self.assertTrue(login.get_header("User-agent"))

    def test_a_blank_key_is_not_asked_about(self):
        # release.yml refuses blank keys in a step of its own, before this runs.
        answers = Answers()
        code, out = run(answers, {"TMDB_API_KEY": TMDB_V3})
        self.assertEqual(code, 0)
        self.assertEqual([r.host for r in answers.requests], ["api.themoviedb.org"])


if __name__ == "__main__":
    unittest.main()
