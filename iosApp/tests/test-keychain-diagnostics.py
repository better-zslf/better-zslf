#!/usr/bin/env python3
"""Offline fixtures only: no xcrun, codesign, Keychain, native app or HTTP is executed."""
import copy
import json
from pathlib import Path
import plistlib
import re
import shutil
import struct
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import ios_keychain_diagnostics as diag
import ios_simulator_smoke as smoke

BUNDLE = "io.github.betterzslf"
OWN = "DDDDDDDD-DDDD-4DDD-8DDD-DDDDDDDDDDDD"


def report(phase="seed", ok=True):
    return dict(schema=1, phase=phase, probeComplete=True, probeOk=ok,
                graphComplete=True, graphOk=ok, sessionAbsent=True if ok else None,
                events=[dict(stage="APP_KEYCHAIN_READ", result="MISSING" if ok else "OSSTATUS",
                             status=-25300 if ok else -34018)])  # Synthetic fixture, NOT an observed OSStatus.


class FakeRunner:
    def __init__(self, container):
        self.container = container
        self.calls = []
        self.timeouts = {}
        self.launches = []
        self.pid = None
        self.next_pid = 1000
        self.fail_signed = False
        self.omit_unsigned = False
        self.timeout_labels = set()
        self.launch_failure_labels = set()

    def call(self, args, label, timeout=60):
        self.calls.append((list(args), label))
        self.timeouts[label] = timeout
        if label in self.timeout_labels:
            raise smoke.subprocess.TimeoutExpired("synthetic read-only command", timeout)
        if args[0] == "get_app_container":
            return str(self.container) + "\n"
        if args[0] == "terminate":
            self.pid = None
        if args[:3] == ["spawn", OWN, "launchctl"]:
            return "PID\tStatus\tLabel\n" + (f"{self.pid}\t0\tUIKitApplication:{BUNDLE}[fixture]\n" if self.pid else "")
        if args[0] == "io":
            Path(args[3]).write_bytes(b"\x89PNG\r\n\x1a\n" + struct.pack(">I", 13) + b"IHDR" + struct.pack(">II", 390, 844))
        return ""

    def call_env(self, args, label, env, timeout=60):
        self.calls.append((list(args), label))
        self.timeouts[label] = timeout
        self.launches.append(copy.deepcopy(env))
        if label in self.launch_failure_labels:
            raise RuntimeError("Synthetic launch denied before PID")
        self.next_pid += 1
        self.pid = self.next_pid
        signed = env["SIMCTL_CHILD_BETTER_ZSLF_PROBE_NAMESPACE"].endswith("_signed")
        if signed or not self.omit_unsigned:
            value = report(env["SIMCTL_CHILD_BETTER_ZSLF_STARTUP_PROBE"], signed and not self.fail_signed)
            path = self.container / diag.REPORT_RELATIVE
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(value), encoding="utf-8")
        return f"{BUNDLE}: {self.pid}\n"


class DiagnosticsTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.artifacts = self.root / "evidence"
        self.artifacts.mkdir()
        self.container = self.root / "owned-container"
        self.container.mkdir()
        self.app = self.root / "Debug-iphonesimulator" / "BetterZslf.app"
        self.app.mkdir(parents=True)
        self.info = dict(CFBundleIdentifier=BUNDLE, CFBundleExecutable="BetterZslf",
                         CFBundleSupportedPlatforms=["iPhoneSimulator"], DTPlatformName="iphonesimulator")
        with (self.app / "Info.plist").open("wb") as stream:
            plistlib.dump(self.info, stream)
        (self.app / "BetterZslf").write_bytes(b"\xcf\xfa\xed\xfeSYNTHETIC-NOT-EXECUTABLE")
        self.runner = FakeRunner(self.container)
        self.pids = []

    def signer(self, artifacts, source, bundle):
        target = artifacts / "signed" / source.name
        target.parent.mkdir()
        shutil.copytree(source, target)
        return target

    def run_probe(self):
        return diag.run_diagnostics(self.artifacts, self.app, BUNDLE, OWN, self.runner,
                                    self.pids.append, sleep=lambda _: None, signer=self.signer,
                                    clock=lambda: 0.0)

    def test_unsigned_failure_is_preserved_and_signed_seed_verify_pass(self):
        result = self.run_probe()
        self.assertEqual(result["result"], "keychain-diagnostics-passed")
        self.assertEqual(result["unsigned"]["result"], "observed-failure")
        self.assertEqual(result["unsigned"]["report"]["events"][0]["status"], -34018)
        self.assertEqual(len(set(self.pids)), 3)
        launches = self.runner.launches
        self.assertEqual([v["SIMCTL_CHILD_BETTER_ZSLF_STARTUP_PROBE"] for v in launches], ["seed", "seed", "verify"])
        self.assertEqual(launches[1]["SIMCTL_CHILD_BETTER_ZSLF_PROBE_NAMESPACE"], launches[2]["SIMCTL_CHILD_BETTER_ZSLF_PROBE_NAMESPACE"])
        self.assertNotEqual(launches[0]["SIMCTL_CHILD_BETTER_ZSLF_PROBE_NAMESPACE"], launches[1]["SIMCTL_CHILD_BETTER_ZSLF_PROBE_NAMESPACE"])
        self.assertEqual(smoke.png_dimensions(self.artifacts / "unsigned-startup.png"), (390, 844))
        self.assertEqual(result["pid"], self.pids[-1])
        for label in ("unsigned-install", "signed-install"):
            self.assertEqual(self.runner.timeouts[label], 90)
        for label in ("unsigned-launch", "signed-seed-launch", "signed-verify-launch"):
            self.assertEqual(self.runner.timeouts[label], 30)
        self.assertEqual(self.runner.timeouts["signed-verify-container"], 15)
        self.assertEqual(self.runner.timeouts["signed-seed-after-terminate"], 5)
        self.assertTrue(all(args[1] == OWN for args, _ in self.runner.calls))
        self.assertFalse((self.artifacts / "startup.png").exists())  # Parent takes final stability screenshot.

    def test_container_timeout_is_retried_before_one_unsigned_launch(self):
        self.runner.timeout_labels.add("unsigned-container")
        value = self.run_probe()
        self.assertEqual(value["result"], "keychain-diagnostics-passed")
        labels = [label for _, label in self.runner.calls]
        self.assertLess(labels.index("unsigned-container"), labels.index("unsigned-container-retry"))
        self.assertLess(labels.index("unsigned-container-retry"), labels.index("unsigned-launch"))
        self.assertEqual(self.runner.timeouts["unsigned-container"], 15)
        self.assertEqual(self.runner.timeouts["unsigned-container-retry"], 15)
        self.assertEqual(len(self.runner.launches), 3)

    def test_exhausted_container_timeout_does_not_hide_unlaunched_a(self):
        self.runner.timeout_labels.update(("unsigned-container", "unsigned-container-retry"))
        with self.assertRaisesRegex(RuntimeError, "Unsigned baseline"):
            self.run_probe()
        value = json.loads((self.artifacts / "keychain-diagnostics.json").read_text())
        self.assertIsNone(value["unsigned"]["report"])
        self.assertEqual(value["unsigned"]["error"]["type"], "TimeoutExpired")
        self.assertEqual(value["signed"]["result"], "passed")
        self.assertEqual(value["result"], "keychain-diagnostics-failed")
        self.assertEqual(len(self.runner.launches), 2)

    def test_launch_denial_gets_scoped_logs_without_relaunch(self):
        self.runner.launch_failure_labels.add("signed-seed-launch")
        with self.assertRaisesRegex(RuntimeError, "Synthetic launch denied"):
            self.run_probe()
        self.assertEqual(len(self.runner.launches), 2)
        logs = [(args, label) for args, label in self.runner.calls if label == "signed-seed-launch-system-log"]
        self.assertEqual(len(logs), 1)
        args = logs[0][0]
        self.assertEqual(args[:3], ["spawn", OWN, "log"])
        self.assertEqual(args[args.index("--last") + 1], "3m")
        predicate = args[args.index("--predicate") + 1]
        for part in ("SpringBoard", "runningboardd", "amfid", "installd", BUNDLE):
            self.assertIn(part, predicate)
        self.assertEqual(self.runner.timeouts[logs[0][1]], 15)
        value = json.loads((self.artifacts / "keychain-diagnostics.json").read_text())
        self.assertIn("failure_launch_log", value["signed"]["seed"])
        self.assertEqual(value["error"]["message"], "Synthetic launch denied before PID")

    def test_signing_failure_outer_capture_preserves_original_error(self):
        def fail_signer(*_):
            raise RuntimeError("Synthetic signing failure")
        with self.assertRaisesRegex(RuntimeError, "Synthetic signing failure"):
            diag.run_diagnostics(self.artifacts, self.app, BUNDLE, OWN, self.runner,
                                 self.pids.append, sleep=lambda _: None, signer=fail_signer,
                                 clock=lambda: 0.0)
        value = json.loads((self.artifacts / "keychain-diagnostics.json").read_text())
        self.assertEqual(value["error"]["message"], "Synthetic signing failure")
        self.assertTrue(any(label.startswith("signed-") and label.endswith("-launch-system-log")
                            for _, label in self.runner.calls))
        self.assertEqual(len(self.runner.launches), 1)

    def test_signed_failure_never_becomes_pass_and_captures_evidence(self):
        self.runner.fail_signed = True
        with self.assertRaisesRegex(RuntimeError, "Signed native"):
            self.run_probe()
        value = json.loads((self.artifacts / "keychain-diagnostics.json").read_text())
        self.assertEqual(value["result"], "keychain-diagnostics-failed")
        self.assertEqual(len(self.runner.launches), 2)
        self.assertTrue((self.artifacts / "signed-seed-failure.png").is_file())

    def test_missing_unsigned_report_cannot_be_hidden_by_signed_success(self):
        self.runner.omit_unsigned = True
        with self.assertRaisesRegex(RuntimeError, "Unsigned baseline"):
            self.run_probe()
        self.assertEqual(len(self.runner.launches), 3)
        value = json.loads((self.artifacts / "keychain-diagnostics.json").read_text())
        self.assertEqual(value["result"], "keychain-diagnostics-failed")

    def test_event_limit_matches_native_256(self):
        value = report()
        value["events"] *= 256
        diag.validate_report(value, "seed")
        value["events"].append(value["events"][0])
        with self.assertRaises(diag.ReportError):
            diag.validate_report(value, "seed")

    def test_schema_rejects_value_fields_and_non_integer_status(self):
        for mutation in (lambda x: x.update(token="fixture"),
                         lambda x: x["events"][0].update(value="fixture"),
                         lambda x: x["events"][0].update(status=True),
                         lambda x: x.update(phase="verify")):
            value = report()
            mutation(value)
            with self.subTest(value=value), self.assertRaises(diag.ReportError):
                diag.validate_report(value, "seed")

    def test_native_report_duplicate_oversize_and_stale_rejected(self):
        path = self.container / diag.REPORT_RELATIVE
        path.parent.mkdir(parents=True)
        for text in ('{"schema":1,"schema":1}', 'x' * (diag.MAX_REPORT_BYTES + 1), ''):
            path.write_text(text)
            with self.assertRaises(diag.ReportError):
                diag.read_native_report(self.container, "seed")
        path.write_text(json.dumps(report("verify")))
        with self.assertRaises(diag.ReportError):
            diag.read_native_report(self.container, "seed")
        self.assertTrue(diag._clear_report(self.container))
        self.assertFalse(path.exists())

    def test_copy_only_signing_commands_and_fixed_ci_entitlements(self):
        calls = []
        def run(command, **kwargs):
            calls.append(command)
            return mock.Mock(returncode=0, stdout=b"", stderr=b"")
        original = (self.app / "BetterZslf").read_bytes()
        with mock.patch.object(diag.sys, "platform", "darwin"):
            target = diag.sign_simulator_copy(self.artifacts, self.app, BUNDLE, run=run)
        self.assertEqual((self.app / "BetterZslf").read_bytes(), original)
        self.assertNotEqual(target, self.app)
        self.assertTrue(all(str(self.app) not in c for c in calls))
        self.assertIn("-", next(c for c in calls if "--sign" in c))
        with (target.parent / "CI-simulator-entitlements.plist").open("rb") as stream:
            entitlements = plistlib.load(stream)
        self.assertEqual(entitlements["keychain-access-groups"], ["CIKEYCHAIN." + BUNDLE])

    def test_release_and_non_simulator_products_rejected(self):
        other = self.root / "Release-iphonesimulator" / self.app.name
        other.parent.mkdir()
        shutil.copytree(self.app, other)
        with self.assertRaises(ValueError):
            diag._source_app(other)
        self.info["CFBundleSupportedPlatforms"] = ["iPhoneOS"]
        with (self.app / "Info.plist").open("wb") as stream:
            plistlib.dump(self.info, stream)
        with self.assertRaises(ValueError):
            diag._source_app(self.app)

    def test_smoke_diagnostic_mode_preserves_parent_liveness_and_review_boundary(self):
        sys.path.insert(0, str(ROOT / "scripts/tests"))
        from test_ios_simulator_smoke import FakeSimctl
        runner = FakeSimctl()
        evidence = dict(result="synthetic-fixture-passed", pid=4321)
        with mock.patch.object(diag, "run_diagnostics", return_value=evidence) as probe:
            value = smoke.smoke(self.artifacts, self.app, runner, sleep=lambda _: None, keychain_diagnostics=True)
        probe.assert_called_once()
        self.assertEqual(value["result"], "startup-keychain-smoke-passed")
        self.assertFalse(value["login_ui_verified"])
        self.assertEqual(value["pid"], 4321)
        self.assertEqual(smoke.png_dimensions(self.artifacts / "startup.png"), (390, 844))
        self.assertNotIn("launch", [args[0] for args, _, _ in runner.calls])

    def test_added_launch_environment_is_allowlisted_before_spawn(self):
        with mock.patch.object(smoke.subprocess, "Popen") as spawn:
            with self.assertRaises(ValueError):
                smoke.Simctl(self.artifacts).call_env(["launch", OWN, BUNDLE], "bad", {"SECRET": "fixture"})
            spawn.assert_not_called()


class NativeSourceGuards(unittest.TestCase):
    def test_literal_native_event_stages_match_host_allowlist(self):
        directory = ROOT / "shared/src/iosMain/kotlin/io/github/betterzslf/platform"
        for name in ("IosStartupDiagnostics.kt", "KeychainKeyValueStore.kt", "PlatformServices.ios.kt"):
            text = (directory / name).read_text()
            for token in re.findall(r'(?:event|verify|bridgeType|stage)\("([^"$]+)"', text):
                self.assertIsNotNone(diag.EVENT_RE.fullmatch(token), (name, token))

    def test_probe_gate_redaction_offline_and_normal_app_path(self):
        directory = ROOT / "shared/src/iosMain/kotlin/io/github/betterzslf"
        native = (directory / "platform/IosStartupDiagnostics.kt").read_text()
        main = (directory / "MainViewController.kt").read_text()
        storage = (directory / "platform/KeychainKeyValueStore.kt").read_text()
        for part in ("Platform.isDebugBinary", '"DTPlatformName") == "iphonesimulator"', "phase != null && namespace != null",
                     'events.size < 256', 'error("CI startup probe networking is disabled")', '"RESTORE_AFTER_RESTART"',
                     '"DELETE_AFTER_RESTART"', '"EMPTY_VALUE"', '"KEEP_OTHER_NAMESPACE"'):
            self.assertIn(part, native)
        self.assertNotIn("error.message", native)
        self.assertNotIn("stackTrace", native)
        self.assertIn("if (!IosStartupDiagnostics.enabled)", main)
        self.assertIn("DisposableEffect(owner)", main)
        self.assertIn("graph?.close()", main)
        self.assertIn("kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly", storage)
        self.assertNotIn("NSUserDefaults", storage)
        for op in ("read", "write", "update", "delete"):
            self.assertIn(f'IosStartupDiagnostics.keychainStatus(service, "{op}"', storage)


if __name__ == "__main__":
    unittest.main(verbosity=2)
