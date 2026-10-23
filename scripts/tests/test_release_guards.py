"""Offline unit checks; these never run Gradle, contact services, or sign an app."""
import base64
import contextlib
import io
import json
import os
import re
import textwrap
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_signing import REQUIRED, decode_keystore
from release_metadata import APPLICATION_IDS, load_version


class VersionTests(unittest.TestCase):
    def check_version(self, text, tag=None, edition="standard"):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "version.properties"
            path.write_text(text, encoding="utf-8")
            return load_version(path, tag, edition)

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

    def test_pro_suffix_keeps_base_version_and_stable_code(self):
        self.assertEqual(self.check_version("VERSION_NAME=1.2.3\nVERSION_CODE=42\n", "v1.2.3-pro", "pro"),
                         {"versionName": "1.2.3-pro", "versionCode": 42, "tag": "v1.2.3-pro"})

    def test_pro_rejects_unsuffixed_tag(self):
        with self.assertRaisesRegex(ValueError, "Tag must exactly"):
            self.check_version("VERSION_NAME=1.2.3\nVERSION_CODE=42\n", "v1.2.3", "pro")

    def test_standard_rejects_pro_tag(self):
        with self.assertRaisesRegex(ValueError, "Tag must exactly"):
            self.check_version("VERSION_NAME=1.2.3\nVERSION_CODE=42\n", "v1.2.3-pro")

    def test_edition_is_closed_set(self):
        for edition in ("", "Pro", "standard-pro", "../../pro", "pro;echo BAD"):
            with self.subTest(edition=edition), self.assertRaisesRegex(ValueError, "Edition"):
                self.check_version("VERSION_NAME=1.2.3\nVERSION_CODE=42\n", edition=edition)

    def test_pro_properties_still_use_numeric_ios_marketing_version(self):
        with self.assertRaisesRegex(ValueError, "numeric x.y.z"):
            self.check_version("VERSION_NAME=1.2.3-pro\nVERSION_CODE=42\n", edition="pro")

    def test_optional_rc_keeps_numeric_marketing_base_and_exact_full_tag(self):
        properties = "VERSION_NAME=1.0.0\nVERSION_CODE=2\nVERSION_PRERELEASE=rc1\n"
        self.assertEqual(self.check_version(properties, "v1.0.0-rc1"),
                         {"versionName": "1.0.0-rc1", "versionCode": 2, "tag": "v1.0.0-rc1"})
        self.assertEqual(self.check_version(properties, "v1.0.0-rc1-pro", "pro"),
                         {"versionName": "1.0.0-rc1-pro", "versionCode": 2, "tag": "v1.0.0-rc1-pro"})
        self.assertEqual(self.check_version(properties.replace("rc1", "rc999999"))["versionName"], "1.0.0-rc999999")

    def test_rc_is_bounded_and_cannot_be_empty_or_inject_an_edition(self):
        for suffix in ("", "rc0", "rc01", "rc1000000", "rc.1", "RC1", "alpha1", "rc1-pro", "rc1+build", "rc1;echo BAD", "rc1$(id)"):
            with self.subTest(suffix=suffix), self.assertRaisesRegex(ValueError, "VERSION_PRERELEASE"):
                self.check_version(f"VERSION_NAME=1.0.0\nVERSION_CODE=2\nVERSION_PRERELEASE={suffix}\n")
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.check_version("VERSION_NAME=1.0.0\nVERSION_CODE=2\nVERSION_PRERELEASE=rc1\nVERSION_PRERELEASE=rc2\n")

    def test_rc_cannot_match_another_channel_version_or_edition_tag(self):
        properties = "VERSION_NAME=1.0.0\nVERSION_CODE=2\nVERSION_PRERELEASE=rc1\n"
        for tag in ("v1.0.0", "v1.0.0-rc2", "v1.0.1-rc1", "v1.0.0-rc1-pro"):
            with self.subTest(tag=tag), self.assertRaisesRegex(ValueError, "Tag must exactly"):
                self.check_version(properties, tag)
        with self.assertRaisesRegex(ValueError, "Tag must exactly"):
            self.check_version(properties, "v1.0.0-rc1", "pro")

    def test_cli_emits_channel_without_changing_three_key_metadata_schema(self):
        import release_metadata
        for suffix, expected in (("", "false"), ("VERSION_PRERELEASE=rc1\n", "true")):
            with self.subTest(suffix=suffix), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                props, output, github = root / "version.properties", root / "meta.json", root / "github-output"
                props.write_text("VERSION_NAME=1.0.0\nVERSION_CODE=2\n" + suffix, encoding="utf-8")
                with mock.patch.object(sys, "argv", ["release_metadata.py", "--properties", str(props), "--output", str(output), "--github-output"]), mock.patch.dict(os.environ, {"GITHUB_OUTPUT": str(github)}, clear=True), contextlib.redirect_stdout(io.StringIO()):
                    release_metadata.main()
                self.assertEqual(set(json.loads(output.read_text(encoding="utf-8"))), {"versionName", "versionCode", "tag"})
                self.assertIn(f"prerelease={expected}\n", github.read_text(encoding="utf-8"))

    def test_package_identity_is_explicit(self):
        self.assertEqual(APPLICATION_IDS, {"standard": "io.github.betterzslf", "pro": "io.github.betterzslf.pro"})


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


class ReleaseTaskTests(unittest.TestCase):
    def test_release_runner_uses_supported_variant_aggregate(self):
        script = (Path(__file__).resolve().parents[1] / "ci-android-release.sh").read_text(encoding="utf-8")
        tasks = []
        for line in script.splitlines():
            token = line.strip().rstrip("\\").strip()
            if re.fullmatch(r":[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)+", token):
                tasks.append(token)
        self.assertEqual(tasks, [":shared:jvmTest", ":androidApp:test", ":androidApp:lintRelease",
                                 ":androidApp:assembleRelease", ":androidApp:bundleRelease"])
        self.assertNotIn(":androidApp:testReleaseUnitTest", script)


class SourceReleaseWorkflowContractTests(unittest.TestCase):
    """Source-shape contracts, not GitHub event execution or network proof."""
    def workflow(self):
        return (Path(__file__).resolve().parents[2] / ".github/workflows/release.yml").read_text(encoding="utf-8")

    def run_intent(self, tag, event="push", prerelease=False):
        block = self.workflow().split("- name: Validate the exact repository, event and bounded ref\n", 1)[1].split("      - name:", 1)[0]
        script = textwrap.dedent(block.split("python3 - <<'PY'\n", 1)[1].rsplit("\n          PY", 1)[0])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            event_file, output = root / "event.json", root / "output"
            event_file.write_text(json.dumps({"release": {"draft": False, "prerelease": prerelease,
                "html_url": "https://github.com/better-zslf/better-zslf/releases/tag/" + tag}}), encoding="utf-8")
            environment = {"GITHUB_REPOSITORY":"better-zslf/better-zslf", "GITHUB_EVENT_NAME":event,
                "EVENT_SOURCE_TAG":tag, "GITHUB_REF":"refs/tags/" + tag,
                "GITHUB_EVENT_PATH":str(event_file), "GITHUB_OUTPUT":str(output)}
            with mock.patch.dict(os.environ, environment, clear=True):
                exec(compile(script, "source-release-intent", "exec"), {})
            return output.read_text(encoding="utf-8")

    def test_push_intent_accepts_only_exact_stable_or_bounded_rc_source_tags(self):
        for tag in ("v1.0.0", "v1.0.0-rc1", "v1.0.0-rc999999"):
            with self.subTest(tag=tag):
                self.assertIn("source_tag=" + tag + "\n", self.run_intent(tag))
        for tag in ("v1.0.0-rc0", "v1.0.0-rc01", "v1.0.0-rc1000000", "v1.0.0-rc.1", "v1.0.0-beta1", "v1.0.0-rc1-pro", "v01.0.0-rc1", "v1.0.0-rc1\ninjected=true"):
            with self.subTest(tag=tag), self.assertRaises(SystemExit):
                self.run_intent(tag)

    def test_published_release_channel_must_match_tag_with_literal_boolean(self):
        for tag, channel in (("v1.0.0", False), ("v1.0.0-rc1", True)):
            with self.subTest(tag=tag):
                self.assertIn("publish_standard=false\n", self.run_intent(tag, "release", channel))
            for invalid in (not channel, str(channel).lower(), 0, 1, None):
                with self.subTest(tag=tag, channel=invalid), self.assertRaises(SystemExit):
                    self.run_intent(tag, "release", invalid)

    def test_rc_publication_does_not_replace_latest_and_notification_rechecks_channel(self):
        workflow = self.workflow()
        self.assertIn("RELEASE_PRERELEASE: ${{ steps.version.outputs.prerelease }}", workflow)
        self.assertIn("true) flags+=(--prerelease --latest=false)", workflow)
        self.assertIn('"${flags[@]}"', workflow)
        self.assertEqual(workflow.count("release.get('prerelease') is not ('-rc' in tag)"), 2)
        self.assertIn("published standard artifacts are immutable", workflow)

    def test_published_and_push_checkouts_pin_event_sha_and_recheck_tag(self):
        workflow = self.workflow()
        self.assertIn("ref: ${{ github.event_name == 'workflow_dispatch' && steps.request.outputs.source_ref || github.sha }}", workflow)
        self.assertNotIn("ref: ${{ github.event_name == 'push' && github.sha ||", workflow)
        self.assertIn("revision('refs/tags/' + tag + '^{commit}') != sha", workflow)

    def test_manual_notification_does_not_enter_tag_only_signing_environment(self):
        workflow = self.workflow()
        signing, notify = workflow.split("\n  release:\n", 1)[1].split("\n  notify:\n", 1)
        self.assertIn("    environment: release\n", signing)
        self.assertNotIn("    environment:", notify)
        self.assertIn("PRO_CI_DISPATCH_TOKEN: ${{ steps.pro-dispatch-token.outputs.token }}", notify)
        self.assertIn("SOURCE_RELEASE_TOKEN: ${{ github.token }}", notify)

    def test_dispatch_app_is_pinned_and_single_repository_actions_only(self):
        notify = self.workflow().split("\n  notify:\n", 1)[1]
        action = "actions/create-github-app-token@64ab729a6294000f5aa4a4bc0b73d1f32bc45510 # v3"
        self.assertEqual(notify.count(action), 1)
        app = notify.split("      - name: Mint a single-repository Pro dispatch App token\n", 1)[1].split("      - name:", 1)[0]
        for required in ("client-id: ${{ vars.CI_APP_CLIENT_ID }}", "private-key: ${{ secrets.CI_APP_PRIVATE_KEY }}",
                         "owner: better-zslf", "repositories: pro-ci", "permission-actions: write"):
            self.assertIn(required, app)
        self.assertEqual(re.findall(r"(?m)^          owner: (.+)$", app), ["better-zslf"])
        self.assertEqual(re.findall(r"(?m)^          repositories: (.+)$", app), ["pro-ci"])
        self.assertEqual(re.findall(r"(?m)^          (permission-[a-z-]+): (.+)$", app), [("permission-actions", "write")])
        self.assertNotIn("permission-contents:", app)
        self.assertNotIn("skip-token-revoke:", app)
        self.assertNotIn("secrets.PRO_CI_DISPATCH_TOKEN", notify)
        self.assertNotIn("secrets.SOURCE_READ_TOKEN", notify)
        self.assertLess(notify.index(action), notify.index("PRO_CI_DISPATCH_TOKEN: ${{ steps.pro-dispatch-token.outputs.token }}"))

    def test_app_private_key_is_action_input_only_and_not_a_job_output(self):
        notify = self.workflow().split("\n  notify:\n", 1)[1]
        self.assertNotRegex(notify, r"(?m)^    env:")
        key_lines = [line.strip() for line in notify.splitlines() if "${{ secrets.CI_APP_PRIVATE_KEY }}" in line]
        self.assertEqual(key_lines, ["private-key: ${{ secrets.CI_APP_PRIVATE_KEY }}"])
        self.assertNotIn("CI_APP_PRIVATE_KEY", notify.split("        run: |", 1)[1])
        self.assertNotRegex(self.workflow(), r"(?m)^      [A-Za-z0-9_-]+:.*steps\.[^.]+\.outputs\.token")


if __name__ == "__main__":
    unittest.main()
