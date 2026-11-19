"""Public Standard metadata API/CLI tests; offline and independent of the private tree."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("public_release_metadata_under_test", ROOT / "scripts/release_metadata.py")
metadata = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metadata)


class StandardMetadataTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.props = self.root / "version.properties"
        self.props.write_text("VERSION_NAME=1.0.0\nVERSION_CODE=59\nVERSION_PRERELEASE=rc58\n", encoding="utf-8")

    def test_api_defaults_to_standard_and_retains_schema(self):
        self.assertEqual(metadata.load_version(self.props), {
            "versionName": "1.0.0-rc58", "versionCode": 59, "tag": "v1.0.0-rc58",
        })
        self.assertEqual(metadata.APPLICATION_IDS, {"standard": "io.github.betterzslf"})

    def test_api_rejects_pro_before_reading_properties(self):
        for edition in ("pro", "Pro", "", "enterprise", "pro;echo injected"):
            with self.subTest(edition=edition), self.assertRaisesRegex(ValueError, "Edition must be standard"):
                metadata.load_version(self.root / "missing.properties", edition=edition)

    def test_cli_defaults_to_standard(self):
        output = self.root / "metadata.json"
        github = self.root / "github-output"
        with mock.patch.object(sys, "argv", ["release_metadata.py", "--properties", str(self.props), "--output", str(output), "--github-output"]), mock.patch.dict(os.environ, {"GITHUB_OUTPUT": str(github)}, clear=True), contextlib.redirect_stdout(io.StringIO()):
            metadata.main()
        self.assertEqual(json.loads(output.read_text(encoding="utf-8")), metadata.load_version(self.props))
        self.assertIn("version_name=1.0.0-rc58\n", github.read_text(encoding="utf-8"))
        self.assertIn("prerelease=true\n", github.read_text(encoding="utf-8"))

    def test_cli_rejects_pro_without_writing_an_output(self):
        output = self.root / "metadata.json"
        with mock.patch.object(sys, "argv", ["release_metadata.py", "--properties", str(self.props), "--edition", "pro", "--output", str(output)]), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
            metadata.main()
        self.assertEqual(raised.exception.code, 2)
        self.assertFalse(output.exists())

    def test_standard_tag_exactness_rejects_private_suffix(self):
        for tag in ("v1.0.0-rc58-pro", "v1.0.0", "v1.0.0-rc59"):
            with self.subTest(tag=tag), self.assertRaisesRegex(ValueError, "Tag must exactly"):
                metadata.load_version(self.props, tag)

    def test_invalid_rc_and_unknown_properties_stay_rejected(self):
        for suffix in ("", "rc0", "rc01", "rc1000000", "rc58-pro", "rc58;echo BAD"):
            self.props.write_text(f"VERSION_NAME=1.0.0\nVERSION_CODE=59\nVERSION_PRERELEASE={suffix}\n", encoding="utf-8")
            with self.subTest(suffix=suffix), self.assertRaises(ValueError):
                metadata.load_version(self.props)
        self.props.write_text("VERSION_NAME=1.0.0\nVERSION_CODE=59\nOTHER=1\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            metadata.load_version(self.props)

    def test_stable_numeric_source_and_maximum_code(self):
        self.props.write_text("VERSION_NAME=2.3.4\nVERSION_CODE=2100000000\n", encoding="utf-8")
        self.assertEqual(metadata.load_version(self.props, "v2.3.4")["versionName"], "2.3.4")
        for code in ("0", "01", "2100000001", "9" * 5000):
            self.props.write_text(f"VERSION_NAME=2.3.4\nVERSION_CODE={code}\n", encoding="utf-8")
            with self.subTest(code=code[:30]), self.assertRaises(ValueError):
                metadata.load_version(self.props)

    def test_product_metadata_has_no_private_checkout_dependency(self):
        source = (ROOT / "scripts/release_metadata.py").read_text(encoding="utf-8")
        self.assertNotIn("work/pro-ci", source)
        self.assertNotIn("sys.path", source)


if __name__ == "__main__":
    unittest.main()
