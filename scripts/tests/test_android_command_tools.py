"""Offline regression for the macOS setup-android 404; no network or Gradle."""
from pathlib import Path
import sys
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_android_command_tools import FIXTURE, ROOT, select_archive, validate_ci, workflow_pins


class AndroidCommandToolsTests(unittest.TestCase):
    def setUp(self):
        self.repository = ET.parse(FIXTURE).getroot()
        self.workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")

    def test_current_ci_has_host_specific_immutable_pins(self):
        self.assertEqual(workflow_pins(self.workflow), {
            "android": ("linux", "15859902"), "ios": ("macosx", "15641748")})
        self.assertEqual(set(validate_ci(self.workflow, self.repository)), {"android", "ios"})

    def test_mac_archive_matches_official_host_size_and_checksum(self):
        archive = select_archive(self.repository, "macosx", "15641748")
        self.assertEqual(archive["package"], "cmdline-tools;21.0")
        self.assertEqual(archive["url"], "https://dl.google.com/android/repository/commandlinetools-mac-15641748_latest.zip")
        self.assertEqual(archive["size"], 152127646)
        self.assertEqual(archive["sha1"], "b62a5d8cf63ded173b47be867be4ee058ceda6df")

    def test_known_working_linux_build_is_not_changed(self):
        archive = select_archive(self.repository, "linux", "15859902")
        self.assertEqual(archive["host"], "linux")
        self.assertTrue(archive["url"].endswith("commandlinetools-linux-15859902_latest.zip"))

    def test_copying_linux_build_to_mac_reproduces_and_rejects_the_404(self):
        broken = self.workflow.replace("cmdline-tools-version: '15641748'", "cmdline-tools-version: '15859902'")
        with self.assertRaisesRegex(ValueError, "macosx archive"):
            validate_ci(broken, self.repository)

    def test_split_mac_archives_do_not_match_the_v3_generic_url(self):
        with self.assertRaisesRegex(ValueError, "commandlinetools-mac-15859902_latest.zip"):
            select_archive(self.repository, "macosx", "15859902")

    def test_wrong_host_and_unpinned_values_fail_closed(self):
        for host, build in (("linux", "15641748"), ("darwin", "15641748"),
                            ("macosx", "latest"), ("macosx", "")):
            with self.subTest(host=host, build=build), self.assertRaises(ValueError):
                select_archive(self.repository, host, build)

    def test_duplicate_pin_or_mutable_latest_in_workflow_is_rejected(self):
        for malformed in (
            self.workflow.replace("cmdline-tools-version: '15641748'", "cmdline-tools-version: latest"),
            self.workflow.replace("cmdline-tools-version: '15641748'", "cmdline-tools-version: '15641748'\n          cmdline-tools-version: '15641748'"),
        ):
            with self.assertRaises(ValueError):
                workflow_pins(malformed)

    def test_invalid_official_checksum_is_rejected(self):
        checksum = self.repository.find("./remotePackage/archives/archive/complete/checksum")
        checksum.text = "not-a-sha1"
        with self.assertRaisesRegex(ValueError, "invalid size/checksum"):
            select_archive(self.repository, "macosx", "15641748")


if __name__ == "__main__":
    unittest.main()
