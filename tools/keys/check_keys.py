#!/usr/bin/env python3
"""Asks TMDB and OpenSubtitles whether Settee's built-in keys still work, before a release builds.

release.yml runs it with the OPENSUBTITLES_API_KEY and TMDB_API_KEY secrets in the environment.
The build already refuses a key in the wrong format (buildSrc/BuiltInKeys.kt), but a key in the
right format can still be the wrong one, or one the service has turned down.

It fails the release only on each service's own answer that the key is invalid:
  TMDB           401 with "status_code": 7 in the JSON, from /3/configuration
  OpenSubtitles  403 with "You cannot consume this service" in the JSON, from /login with
                 placeholder credentials (sign-ins are never cached; searches are, by Cloudflare,
                 whatever the key, so a search can't tell)
Anything else — a timeout, a 5xx, a 429, an HTML page such as a Cloudflare challenge — is a
warning on the run and the release goes on: a service being down must never block a release.

It never prints a key, nor a URL or error message that could hold one. A blank key is skipped:
release.yml refuses those in a step of its own.

Exits 1 if a key was turned down, else 0. Prints GitHub Actions ::error:: and ::warning:: lines.
"""

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

TIMEOUT_SECONDS = 15
TMDB_CONFIGURATION = "https://api.themoviedb.org/3/configuration"
OPENSUBTITLES_LOGIN = "https://api.opensubtitles.com/api/v1/login"
# OpenSubtitles turns away requests without one; the app sends "Settee v<version>".
USER_AGENT = "Settee release check"

VALID = "valid"
INVALID = "invalid"
UNSURE = "unsure"


def fetch(request):
    """(status, body) for the request; raises on a timeout or a connection that never answered."""
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def json_object(body):
    """The body as a JSON object, or None for anything else (an HTML page, say)."""
    try:
        parsed = json.loads(body)
    except ValueError:
        return None
    return parsed if isinstance(parsed, dict) else None


def tmdb_request(key):
    # A v4 Read Access Token goes as a Bearer, which TMDB rejects as an api_key, and the reverse
    # for a v3 key: the same split as the app's TmdbClient.
    if key.startswith("eyJ"):
        return urllib.request.Request(
            TMDB_CONFIGURATION, headers={"Authorization": "Bearer " + key, "User-Agent": USER_AGENT}
        )
    query = urllib.parse.urlencode({"api_key": key})
    return urllib.request.Request(TMDB_CONFIGURATION + "?" + query, headers={"User-Agent": USER_AGENT})


def tmdb_verdict(status, body):
    if status == 200:
        return VALID
    answer = json_object(body)
    if status == 401 and answer is not None and answer.get("status_code") == 7:
        return INVALID
    return UNSURE


def opensubtitles_request(key):
    # Made-up credentials: a working key gets "invalid username/password" (401), a turned-down
    # one a 403 before the credentials are looked at.
    body = json.dumps({"username": "settee-release-check", "password": "placeholder"}).encode()
    return urllib.request.Request(
        OPENSUBTITLES_LOGIN,
        data=body,
        method="POST",
        headers={
            "Api-Key": key,
            "User-Agent": USER_AGENT,
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
    )


def opensubtitles_verdict(status, body):
    if status in (200, 401):
        return VALID
    answer = json_object(body)
    if status == 403 and answer is not None and "You cannot consume this service" in str(answer.get("message", "")):
        return INVALID
    return UNSURE


SERVICES = [
    ("TMDB", "TMDB_API_KEY", tmdb_request, tmdb_verdict),
    ("OpenSubtitles", "OPENSUBTITLES_API_KEY", opensubtitles_request, opensubtitles_verdict),
]


def main(env, http, out):
    failed = False
    for name, secret, request_for, verdict_of in SERVICES:
        key = env.get(secret, "")
        if not key:
            continue
        try:
            status, body = http(request_for(key))
        except Exception as e:  # noqa: BLE001 - any failure to get an answer is only a warning
            # The type alone: a message could quote the URL, and TMDB's carries a v3 key.
            print(f"::warning::Couldn't ask {name} whether {secret} works ({type(e).__name__}).", file=out)
            continue
        verdict = verdict_of(status, body)
        if verdict == INVALID:
            print(f"::error::{name} turned down {secret}. Replace the secret, then run Release again.", file=out)
            failed = True
        elif verdict == UNSURE:
            print(f"::warning::{name} gave no clear answer about {secret} (HTTP {status}); releasing anyway.", file=out)
        else:
            print(f"{name} accepted {secret}.", file=out)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(os.environ, fetch, sys.stdout))
