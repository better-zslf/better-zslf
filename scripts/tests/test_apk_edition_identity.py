"""Pure APK badging checks: no SDK, signing, network or subprocess execution."""
from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_release import validate_apk_identity


def badging(package, version="1.2.3", code="42"):
    return f"package: name='{package}' versionCode='{code}' versionName='{version}' platformBuildVersionName='37'\n"


class ApkEditionIdentityTests(unittest.TestCase):
    def test_standard_identity(self):
        self.assertEqual(validate_apk_identity(badging("io.github.betterzslf"), {"versionName":"1.2.3","versionCode":42}, "standard"), "io.github.betterzslf")

    def test_pro_identity(self):
        self.assertEqual(validate_apk_identity(badging("io.github.betterzslf.pro","1.2.3-pro"), {"versionName":"1.2.3-pro","versionCode":42}, "pro"), "io.github.betterzslf.pro")

    def test_other_edition_and_lookalike_packages_are_rejected(self):
        for edition, package in [("pro","io.github.betterzslf"),("standard","io.github.betterzslf.pro"),("pro","io.github.betterzslf.pro.extra")]:
            with self.subTest(edition=edition,package=package),self.assertRaisesRegex(ValueError,"applicationId"):
                validate_apk_identity(badging(package),{"versionName":"1.2.3","versionCode":42},edition)

    def test_pro_suffix_is_required_by_validated_metadata(self):
        with self.assertRaisesRegex(ValueError,"versionCode/versionName"):
            validate_apk_identity(badging("io.github.betterzslf.pro"),{"versionName":"1.2.3-pro","versionCode":42},"pro")

    def test_version_code_cannot_drift(self):
        with self.assertRaisesRegex(ValueError,"versionCode/versionName"):
            validate_apk_identity(badging("io.github.betterzslf",code="43"),{"versionName":"1.2.3","versionCode":42},"standard")

    def test_missing_or_unrecognized_badging_is_rejected(self):
        with self.assertRaisesRegex(ValueError,"applicationId"):
            validate_apk_identity("",{"versionName":"1.2.3","versionCode":42},"standard")

    def test_unknown_edition_is_not_a_fallback(self):
        with self.assertRaisesRegex(ValueError,"Edition"):
            validate_apk_identity(badging("io.github.betterzslf"),{"versionName":"1.2.3","versionCode":42},"Standard")


if __name__=="__main__":unittest.main()
