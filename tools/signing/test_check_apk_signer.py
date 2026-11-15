"""Tests for tools/check-apk-signer.sh, run as release.yml and build.yml run it.

apksigner and keytool are stubs that print fixed digests, in the formats the real tools print, so
these need neither a JDK nor build-tools, and no keystore exists anywhere.
"""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

CHECKER = Path(__file__).resolve().parents[1] / "check-apk-signer.sh"
PIN_FILE = Path(__file__).resolve().parents[1] / "release-cert.sha256"

KEY = "9bf478a053deb0e620aae472991960774508ea609deed86f6ff554d226fffaa1"
OTHER = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"


def keytool_form(digest):
    """keytool prints AB:CD:…"""
    return ":".join(digest[i : i + 2] for i in range(0, len(digest), 2)).upper()


class CheckApkSignerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)

    def stub(self, name, body):
        path = self.dir / name
        path.write_text("#!/bin/sh\n" + body)
        path.chmod(0o755)
        return path

    def run_checker(self, keystore_digest, apk_digest, *pinned):
        self.stub(
            "keytool",
            "cat <<'EOF'\n"
            "Alias name: settee\n"
            "Certificate fingerprints:\n"
            "\t SHA1: 00:11\n"
            f"\t SHA256: {keytool_form(keystore_digest)}\n"
            "EOF\n",
        )
        apksigner = self.stub(
            "apksigner",
            "cat <<'EOF'\n"
            f"V2 Signer: certificate SHA-256 digest: {apk_digest}\n"
            f"V3 Signer: certificate SHA-256 digest: {apk_digest}\n"
            "EOF\n",
        )
        env = dict(
            os.environ,
            PATH=f"{self.dir}{os.pathsep}{os.environ['PATH']}",
            APKSIGNER=str(apksigner),
            KEYSTORE_PASSWORD="p",
        )
        return subprocess.run(
            ["sh", str(CHECKER), "settee.apk", "settee.jks", "settee", *pinned],
            env=env,
            capture_output=True,
            text=True,
            check=False,
        )

    def test_an_apk_matching_the_keystore_but_not_the_pin_fails_naming_both(self):
        result = self.run_checker(OTHER, OTHER, KEY)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertIn(f"pinned: {KEY}", result.stderr)
        self.assertIn(f"found:  {OTHER}", result.stderr)

    def test_an_empty_pin_fails_rather_than_checking_without_one(self):
        """release.yml passes $(cat tools/release-cert.sha256): a missing or emptied file gives ""."""
        result = self.run_checker(KEY, KEY, "")

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertIn("pinned digest is empty", result.stderr)

    def test_an_apk_matching_the_keystore_and_the_pin_prints_only_its_digest(self):
        result = self.run_checker(KEY, KEY, KEY)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, f"{KEY}\n")

    def test_a_pin_written_as_keytool_prints_it_with_a_windows_line_end_still_matches(self):
        result = self.run_checker(KEY, KEY, keytool_form(KEY) + "\r")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, f"{KEY}\n")

    def test_an_apk_matching_the_pin_but_not_the_keystore_fails(self):
        result = self.run_checker(OTHER, KEY, KEY)

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertIn(f"expected: {OTHER}", result.stderr)

    def test_without_a_pin_an_apk_matching_the_keystore_passes_whatever_its_key(self):
        """The PR build checks the debug APK against the debug keystore this way."""
        result = self.run_checker(OTHER, OTHER)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, f"{OTHER}\n")

    def test_without_a_pin_an_apk_not_matching_the_keystore_fails_as_before(self):
        result = self.run_checker(KEY, OTHER)

        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, "")
        self.assertEqual(
            result.stderr,
            "settee.apk is not signed with the release key.\n"
            f"  expected: {KEY}\n"
            f"  found:    {OTHER}\n",
        )


class ReleaseCertPinTest(unittest.TestCase):
    def test_the_committed_pin_is_one_digest_in_the_form_the_checker_prints(self):
        """release.yml passes this file to the checker, so a broken one only shows at release."""
        self.assertRegex(PIN_FILE.read_text(), r"\A[0-9a-f]{64}\n\Z")


if __name__ == "__main__":
    unittest.main()
