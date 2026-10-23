"""Offline unit checks; these never run Gradle, contact services, or sign an app."""
import base64
import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_signing import REQUIRED, decode_keystore
from release_metadata import load_version


class VersionTests(unittest.TestCase):
    def check_version(self, text, tag=None):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "version.properties"
            path.write_text(text, encoding="utf-8")
            return load_version(path, tag)

    def test_current_tag(self):
        self.assertEqual(self.check_version("VERSION_NAME=0.1.0\nVERSION_CODE=1\n", "v0.1.0"),
                         {"versionName": "0.1.0", "versionCode": 1, "tag": "v0.1.0"})

    def test_tag_mismatch(self):
        with self.assertRaisesRegex(ValueError, "Tag must exactly"):
            self.check_version("VERSION_NAME=0.1.0\nVERSION_CODE=1\n", "v0.1.1")

    def test_prerelease_is_not_an_ios_marketing_version(self):
        with self.assertRaisesRegex(ValueError, "numeric x.y.z"):
            self.check_version("VERSION_NAME=1.2.3-rc.1\nVERSION_CODE=42\n", "v1.2.3-rc.1")

    def test_code_bounds(self):
        for code in ("0", "-1", "01", "1.2", "2100000001", "text", ""):
            with self.subTest(code=code), self.assertRaises(ValueError):
                self.check_version(f"VERSION_NAME=0.1.0\nVERSION_CODE={code}\n")
        self.assertEqual(self.check_version("VERSION_NAME=0.1.0\nVERSION_CODE=2100000000\n")["versionCode"], 2100000000)

    def test_name_format_and_injection(self):
        for name in ("0.1", "v0.1.0", "01.1.0", "1.0.0; echo BAD", "1.0.0$(id)", "1.0.0+build"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.check_version(f"VERSION_NAME={name}\nVERSION_CODE=1\n")

    def test_duplicates_unknown_and_missing(self):
        for text in ("VERSION_NAME=0.1.0\nVERSION_CODE=1\nVERSION_CODE=2\n",
                     "VERSION_NAME=0.1.0\n", "VERSION_NAME=0.1.0\nVERSION_CODE=1\nEXTRA=1\n",
                     "VERSION_NAME=0.1.0\nVERSION_CODE=1\nbroken\n"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                self.check_version(text)

    def test_comments_and_crlf(self):
        self.assertEqual(self.check_version("# Version source\r\nVERSION_NAME=0.1.0\r\nVERSION_CODE=1\r\n")["versionName"], "0.1.0")


class SigningInputTests(unittest.TestCase):
    def fixture(self):
        return dict(zip(REQUIRED, (base64.b64encode(b"fixture only").decode(), "fixture-store", "fixture-alias", "fixture-key")))

    def test_valid_base64_and_whitespace(self):
        values = self.fixture()
        self.assertEqual(decode_keystore(values), b"fixture only")
        values[REQUIRED[0]] = "\n" + values[REQUIRED[0]] + "\n"
        self.assertEqual(decode_keystore(values), b"fixture only")

    def test_each_required_secret(self):
        for missing in REQUIRED:
            values = self.fixture()
            del values[missing]
            with self.subTest(missing=missing), self.assertRaisesRegex(ValueError, missing):
                decode_keystore(values)

    def test_invalid_and_empty_base64(self):
        for encoded in ("!!!", "a", " \n "):
            values = self.fixture()
            values[REQUIRED[0]] = encoded
            with self.subTest(encoded=encoded), self.assertRaises(ValueError):
                decode_keystore(values)

    def test_errors_do_not_echo_secret_values(self):
        values = self.fixture()
        values[REQUIRED[0]] = "PRIVATE_SECRET_INVALID!"
        output = io.StringIO()
        with contextlib.redirect_stdout(output), self.assertRaises(ValueError) as raised:
            decode_keystore(values)
        self.assertNotIn("PRIVATE_SECRET", str(raised.exception))
        self.assertEqual(output.getvalue(), "")


if __name__ == "__main__":
    unittest.main()
