"""Offline resolver checks and public CI independence/wiring contracts."""
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ci_workflow_contract import read_test_workflow, test_workflow_path


class WorkflowContractTests(unittest.TestCase):
    def test_no_override_uses_real_public_workflow_independent_of_cwd(self):
        expected = Path(__file__).resolve().parents[2] / ".github/workflows/tests.yml"
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(test_workflow_path(), expected)
            self.assertEqual(read_test_workflow(), expected.read_text(encoding="utf-8"))
        with patch.dict(os.environ, {"BETTER_ZSLF_TEST_WORKFLOW": ""}):
            self.assertEqual(test_workflow_path(), expected)

    def test_relative_path_is_rejected(self):
        with patch.dict(os.environ, {"BETTER_ZSLF_TEST_WORKFLOW": ".github/workflows/tests.yml"}), self.assertRaisesRegex(ValueError, "absolute"):
            test_workflow_path()

    def test_wrong_filename_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".github/workflows/ci.yml"
            path.parent.mkdir(parents=True)
            path.write_text("name: fixture only\n")
            with patch.dict(os.environ, {"BETTER_ZSLF_TEST_WORKFLOW": str(path)}), self.assertRaisesRegex(ValueError, "actual absolute"):
                test_workflow_path()

    def test_actual_file_required_and_read_without_replacement(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".github/workflows/tests.yml"
            with patch.dict(os.environ, {"BETTER_ZSLF_TEST_WORKFLOW": str(path)}), self.assertRaisesRegex(ValueError, "missing"):
                test_workflow_path()
            path.parent.mkdir(parents=True)
            path.write_text("name: resolver unit fixture\n", encoding="utf-8")
            with patch.dict(os.environ, {"BETTER_ZSLF_TEST_WORKFLOW": str(path)}):
                self.assertEqual(read_test_workflow(), path.read_text())


class PublicWorkflowWiringTests(unittest.TestCase):
    root = Path(__file__).resolve().parents[2]

    def test_public_workflow_has_no_private_checkout_secret_or_source_selection(self):
        workflow = read_test_workflow()
        for forbidden in ("secrets:", "secrets.", "create-github-app-token@", "pro-ci", "matrix.edition", "working-directory: source", "BETTER_ZSLF_TEST_WORKFLOW:", "pull_request_target"):
            self.assertNotIn(forbidden, workflow)
        self.assertEqual(workflow.count("persist-credentials: false"), 2)
        self.assertEqual(workflow.count("ref: ${{ needs.resolve.outputs.source_sha }}"), 2)
        self.assertEqual(workflow.count("cache-read-only: true"), 2)
        self.assertIn("contents: read", workflow)
        self.assertIn("re.fullmatch(r'[0-9a-f]{40}', sha)", workflow)
        self.assertIn("ORG_GRADLE_PROJECT_betterZslfEdition: standard", workflow)

    def test_every_action_is_commit_pinned_and_public_build_reuses_tests(self):
        import re
        workflow = read_test_workflow()
        actions = re.findall(r"(?m)^\s*(?:- )?uses:\s*(\S+)", workflow)
        self.assertTrue(actions)
        self.assertTrue(all(re.fullmatch(r"[\w./-]+@[0-9a-f]{40}", action) for action in actions), actions)
        build = (self.root / ".github/workflows/build.yml").read_text(encoding="utf-8")
        self.assertIn("uses: ./.github/workflows/tests.yml", build)
        self.assertIn("source_ref: ${{ inputs.source_ref || github.sha }}", build)
        self.assertNotIn("secrets:", build)

    def test_all_public_modules_have_jvm_and_native_tasks_before_product_smoke(self):
        shell = (self.root / "scripts/ci-android.sh").read_text(encoding="utf-8")
        workflow = read_test_workflow()
        modules = (":core:text", ":core:run-model", ":core:logging", ":core:network-session", ":core:storage", ":platform:storage", ":platform:location", ":design-system", ":feature:run:api",
                   ":feature:run:domain", ":feature:run:data", ":feature:run:presentation", ":feature:run:ui", ":feature:run:runtime",
                   ":feature:services:presentation", ":feature:services:ui", ":platform-ui:readonly-map",
                   ":feature:schedule:api", ":feature:schedule:domain", ":feature:schedule:data", ":feature:schedule:runtime", ":feature:schedule:ui", ":platform:schedule-native",
                   ":feature:home:presentation", ":feature:home:ui", ":feature:update:domain",
                   ":feature:update:data", ":feature:update:presentation", ":feature:update:ui",
                   ":feature:accountrecovery:domain", ":feature:accountrecovery:data", ":feature:accountrecovery:presentation", ":feature:accountrecovery:ui",
                   ":feature:campusnet:domain", ":feature:campusnet:data", ":feature:campusnet:presentation", ":feature:campusnet:ui",
                   ":feature:auth:domain", ":feature:auth:data", ":feature:auth:presentation", ":feature:auth:ui",
                   ":feature:profile:domain", ":feature:profile:data", ":feature:profile:presentation",
                   ":feature:content:domain", ":feature:content:data", ":feature:content:presentation",
                   ":platform:updates", ":shared")
        for module in modules:
            self.assertEqual(shell.count(module + ":jvmTest"), 1)
            self.assertEqual(workflow.count(module + ":iosSimulatorArm64Test"), 1)
        self.assertIn("-PbetterZslfEdition=standard", shell)
        self.assertEqual(shell.count(":shared:testAndroidHostTest"), 1)
        self.assertEqual(shell.count(":platform:updates:testAndroidHostTest"), 1)
        self.assertEqual(shell.count(":platform:schedule-native:testAndroidHostTest"), 1)
        self.assertEqual(shell.count(":core:storage:testAndroidHostTest"), 1)
        self.assertEqual(shell.count(":feature:schedule:runtime:testAndroidHostTest"), 1)
        self.assertEqual(shell.count(":feature:schedule:ui:testAndroidHostTest"), 1)
        # The auth family follows the run/campusnet/accountrecovery precedent: every layer is
        # listed with a Host entry; the NO-SOURCE domain leg is reported honestly and passes trivially.
        for feature in ("accountrecovery", "campusnet", "run", "auth"):
            for layer in ("domain", "data", "presentation", "ui"):
                self.assertEqual(shell.count(":feature:" + feature + ":" + layer + ":testAndroidHostTest"), 1)
        # The run write kernel and the location platform module are listed with the same honest
        # Host-entry precedent: the location module has no host-test sources and passes trivially.
        self.assertEqual(shell.count(":feature:run:runtime:testAndroidHostTest"), 1)
        self.assertEqual(shell.count(":platform:location:testAndroidHostTest"), 1)
        # The V2 closing profile/content verticals follow the same honest NO-SOURCE precedent.
        for family in ("profile", "content"):
            for layer in ("domain", "data", "presentation"):
                self.assertEqual(shell.count(":feature:" + family + ":" + layer + ":testAndroidHostTest"), 1)
        self.assertIn(":platform:android-host:testDebugUnitTest", shell)
        self.assertIn(":platform:android-host:lintDebug", shell)
        for command in ("python3 -B scripts/display_text_guard.py --root .", "python3 -B scripts/architecture_guard.py --root ."):
            self.assertEqual(shell.count(command), 1)
            self.assertLess(shell.index(command), shell.index("bash ./gradlew"))
        self.assertEqual(shell.count("verifyModuleCoordinates"), 1)
        self.assertEqual(workflow.count("verifyModuleCoordinates"), 1)
        self.assertIn(":shared:linkDebugFrameworkIosArm64", workflow)
        self.assertLess(workflow.index(":shared:iosSimulatorArm64Test"), workflow.index("run: bash scripts/ci-ios.sh"))
        self.assertIn("run: python3 -B -m unittest discover -s scripts/tests -v", workflow)

    def test_live_optins_and_credentials_are_cleared_on_both_platform_entries(self):
        import re
        workflow = read_test_workflow()
        shell = (self.root / "scripts/ci-android.sh").read_text(encoding="utf-8")
        names = ("SCHEDULE_LIVE_PROBE", "SCHEDULE_TEST_ACCOUNT", "SCHEDULE_TEST_PASSWORD", "ZSLF_LIVE_TEST", "ZSLF_USERNAME", "ZSLF_PASSWORD")
        for job in ("android", "ios"):
            body = re.search(r"^  " + job + r":\n(.*?)(?=^  [a-z]+:|\Z)", workflow, re.M | re.S).group(1)
            for name in names:
                self.assertRegex(body, r"(?m)^      " + name + r": ''$")
        reset = "unset " + " ".join(names)
        self.assertIn(reset, shell)
        self.assertLess(shell.index(reset), shell.index("bash ./gradlew"))
        # Never pass dummy credentials, inherit a secret, or change the opt-in tests to always skip.
        for name in names:
            self.assertNotIn(name + ": ${{", workflow)
            self.assertNotIn("export " + name + "=", shell)

    def test_reports_use_only_explicit_public_output_scopes(self):
        workflow = read_test_workflow()
        self.assertIn("platform-ui/*/build/test-results/", workflow)
        self.assertIn("platform/updates/build/reports/tests/", workflow)
        self.assertEqual(workflow.count("platform/updates/build/test-results/"), 2)
        self.assertIn("platform/schedule-native/build/reports/tests/", workflow)
        self.assertEqual(workflow.count("platform/schedule-native/build/test-results/"), 2)
        self.assertIn(".local/ios-ci/Logs/", workflow)
        self.assertIn(".local/ios-ci/build.xcresult", workflow)
        self.assertIn("include-hidden-files: true", workflow)
        self.assertNotIn("path: .", workflow)
        self.assertNotIn("**/.local/", workflow)


if __name__ == "__main__":
    unittest.main()
