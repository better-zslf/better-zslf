"""Public-only Python bridge tests; no Gradle, Xcode, signing, or private sources."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("public_ios_version_under_test", ROOT / "iosApp/scripts/version_settings.py")
bridge = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = bridge
spec.loader.exec_module(bridge)


class VersionSettingsTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.props = self.root / "version.properties"
        self.props.write_text("VERSION_NAME=1.0.0\nVERSION_CODE=59\nVERSION_PRERELEASE=rc58\n", encoding="utf-8")

    def xcode_env(self):
        return {"MARKETING_VERSION": "1.0.0", "CURRENT_PROJECT_VERSION": "59", "BETTER_ZSLF_EDITION": "standard",
                "PRODUCT_BUNDLE_IDENTIFIER": "io.github.betterzslf", "BETTER_ZSLF_BUNDLE_ID": "io.github.betterzslf",
                "BETTER_ZSLF_VERSION_NAME": "1.0.0", "BETTER_ZSLF_VERSION_CODE": "59",
                "BETTER_ZSLF_DISPLAY_VERSION": "1.0.0-rc58", "BETTER_ZSLF_VERSION_PRERELEASE": "rc58"}

    def test_standard_is_default_and_config_keeps_numeric_marketing(self):
        version = bridge.prepare_settings(self.props, environ={})
        self.assertEqual((version.edition, version.bundle, version.marketing_version, version.display_version),
                         ("standard", "io.github.betterzslf", "1.0.0", "1.0.0-rc58"))
        self.assertIn("BETTER_ZSLF_EDITION = standard\n", bridge.xcconfig(version))
        self.assertIn("BETTER_ZSLF_VERSION_NAME = 1.0.0\n", bridge.xcconfig(version))

    def test_load_and_prepare_reject_pro(self):
        for edition in ("pro", "Pro", "", "standard-pro", "pro;echo BAD"):
            with self.subTest(edition=edition), self.assertRaisesRegex(ValueError, "standard"):
                bridge.load_properties(self.props, edition)
            with self.subTest(edition=edition), self.assertRaisesRegex(ValueError, "standard"):
                bridge.prepare_settings(self.props, ["-PbetterZslfEdition=" + edition], {})
            with self.subTest(edition=edition), self.assertRaisesRegex(ValueError, "standard"):
                bridge.prepare_settings(self.props, environ={"ORG_GRADLE_PROJECT_betterZslfEdition": edition})

    def test_cli_standard_wins_ambient_pro_but_pro_cli_never_succeeds(self):
        version = bridge.prepare_settings(self.props, ["-PbetterZslfEdition=standard"], {"ORG_GRADLE_PROJECT_betterZslfEdition": "pro"})
        self.assertEqual(version.edition, "standard")
        with self.assertRaises(ValueError):
            bridge.prepare_settings(self.props, ["-PbetterZslfEdition=pro"], {"ORG_GRADLE_PROJECT_betterZslfEdition": "standard"})

    def test_stable_numeric_overrides_and_cli_precedence(self):
        self.props.write_text("VERSION_NAME=1.2.3\nVERSION_CODE=42\n", encoding="utf-8")
        version = bridge.prepare_settings(self.props, ["-PversionName=3.4.5", "-PversionCode=44"],
                                          {"ORG_GRADLE_PROJECT_versionName": "2.3.4", "ORG_GRADLE_PROJECT_versionCode": "43"})
        self.assertEqual((version.marketing_version, version.version_code), ("3.4.5", 44))
        with self.assertRaises(ValueError):
            bridge.prepare_settings(self.props, ["-PversionName=1.2.3-pro"], {})

    def test_rc_override_must_preserve_exact_standard_display(self):
        self.assertEqual(bridge.prepare_settings(self.props, ["-PversionName=1.0.0-rc58"], {}).display_version, "1.0.0-rc58")
        for name in ("1.0.0", "1.0.0-rc59", "1.0.0-rc58-pro", "1.0.1-rc58"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                bridge.prepare_settings(self.props, ["-PversionName=" + name], {})

    def test_invalid_persisted_metadata_is_not_hidden_by_overrides(self):
        self.props.write_text("VERSION_NAME=1.0.0\nVERSION_CODE=59\nVERSION_PRERELEASE=rc0\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            bridge.prepare_settings(self.props, ["-PversionName=2.3.4", "-PversionCode=60"], {})

    def test_numeric_code_and_argument_bounds(self):
        for value in ("0", "01", "2100000001", "9" * 5000):
            with self.subTest(value=value[:30]), self.assertRaises(ValueError):
                bridge.prepare_settings(self.props, ["-PversionCode=" + value], {})
        for argument in ("--unexpected", "-PversionName", "-PversionName=$(id)", "-PpublicDevelopment=true"):
            with self.subTest(argument=argument), self.assertRaises(ValueError):
                bridge.prepare_settings(self.props, [argument], {})

    def test_xcode_standard_defaults_and_identity_checks(self):
        env = self.xcode_env()
        self.assertEqual(bridge.xcode_settings(self.props, env).display_version, "1.0.0-rc58")
        del env["BETTER_ZSLF_EDITION"]
        self.assertEqual(bridge.xcode_settings(self.props, env).edition, "standard")
        for key, value in (("BETTER_ZSLF_EDITION", "pro"), ("PRODUCT_BUNDLE_IDENTIFIER", "io.github.betterzslf.pro"),
                           ("MARKETING_VERSION", "1.0.0-rc58"), ("BETTER_ZSLF_DISPLAY_VERSION", "1.0.0-rc58-pro")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                bridge.xcode_settings(self.props, dict(self.xcode_env(), **{key: value}))

    def test_missing_or_stale_xcode_rc_is_rejected(self):
        for key in ("BETTER_ZSLF_DISPLAY_VERSION", "BETTER_ZSLF_VERSION_PRERELEASE"):
            env = self.xcode_env()
            del env[key]
            with self.subTest(key=key), self.assertRaises(ValueError):
                bridge.xcode_settings(self.props, env)

    def test_cli_rejects_pro_without_creating_generated_config(self):
        with mock.patch.object(bridge, "ROOT", self.root), mock.patch.dict(os.environ, {}, clear=True), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(bridge.main(["prepare", "-PbetterZslfEdition=pro"]), 2)
        self.assertFalse((self.root / "iosApp/.generated/Version.xcconfig").exists())

    def test_write_config_is_idempotent_and_paths_with_spaces_work(self):
        output = self.root / "path with spaces/Version.xcconfig"
        version = bridge.load_properties(self.props)
        bridge.write_config(output, version)
        self.assertEqual(output.read_text(encoding="utf-8"), bridge.xcconfig(version))
        before = output.stat().st_mtime_ns
        bridge.write_config(output, version)
        self.assertEqual(output.stat().st_mtime_ns, before)

    def test_embed_rejects_pro_before_ide_short_circuit(self):
        source = (ROOT / "iosApp/scripts/embed-shared-framework.sh").read_text(encoding="utf-8")
        self.assertLess(source.index('if [[ "$edition" != standard ]]'), source.index("OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED"))
        self.assertIn("ORG_GRADLE_PROJECT_betterZslfEdition-standard", source)
        self.assertNotIn("pro) bundle=", source)
        self.assertIn(":shared:embedAndSignAppleFrameworkForXcode", source)

    def test_all_ios_shell_scripts_use_lf_for_macos_execution(self):
        paths = [ROOT / "scripts/ci-ios.sh", *sorted((ROOT / "iosApp/scripts").glob("*.sh")),
                 ROOT / "iosApp/tests/version-scripts-test.sh"]
        for path in paths:
            with self.subTest(path=path.name):
                self.assertNotIn(b"\r", path.read_bytes())

    def test_ci_is_standard_only_before_native_tools(self):
        source = (ROOT / "scripts/ci-ios.sh").read_text(encoding="utf-8")
        self.assertIn("ORG_GRADLE_PROJECT_betterZslfEdition-standard", source)
        self.assertLess(source.index('[[ "$edition" == standard ]]'), source.index("xcodebuild -version"))
        self.assertNotIn("pro) bundle=", source)


if __name__ == "__main__":
    unittest.main()
