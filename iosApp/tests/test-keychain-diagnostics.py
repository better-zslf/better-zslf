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
        self.fail_labels = set()
        self.process_outputs = {}
        self.screenshot_payloads = {}
        self.report_overrides = {}
        self.launch_pids = {}

    def call(self, args, label, timeout=60):
        self.calls.append((list(args), label))
        self.timeouts[label] = timeout
        if args[0] == "io":
            # Like CI9, an image may exist even when the command later times out/nonzero.
            payload = self.screenshot_payloads.get(label, b"\x89PNG\r\n\x1a\n" + struct.pack(">I", 13) + b"IHDR" + struct.pack(">II", 390, 844))
            Path(args[3]).write_bytes(payload)
        if label in self.timeout_labels:
            raise smoke.subprocess.TimeoutExpired("synthetic read-only command", timeout)
        if label in self.fail_labels:
            raise RuntimeError("Synthetic command failure: " + label)
        if label in self.process_outputs:
            return self.process_outputs[label]
        if args[0] == "uninstall":
            shutil.rmtree(self.container)
            self.container = self.container.with_name("fresh-signed-container")
            self.container.mkdir()
        if args[0] == "get_app_container":
            return str(self.container) + "\n"
        if args[0] == "terminate":
            self.pid = None
        if args[:3] == ["spawn", OWN, "launchctl"]:
            return "PID\tStatus\tLabel\n-\t0\tcom.apple.fixture.daemon\n" + (f"{self.pid}\t0\tUIKitApplication:{BUNDLE}[fixture]\n" if self.pid else "")
        return ""

    def call_env(self, args, label, env, timeout=60):
        self.calls.append((list(args), label))
        self.timeouts[label] = timeout
        self.launches.append(copy.deepcopy(env))
        if label in self.launch_failure_labels:
            raise RuntimeError("Synthetic launch denied before PID")
        self.next_pid += 1
        self.pid = self.launch_pids.get(label, self.next_pid)
        signed = env["SIMCTL_CHILD_BETTER_ZSLF_PROBE_NAMESPACE"].endswith("_signed")
        if signed or not self.omit_unsigned:
            value = report(env["SIMCTL_CHILD_BETTER_ZSLF_STARTUP_PROBE"], signed and not self.fail_signed)
            value.update(self.report_overrides.get(label, {}))
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

    def run_probe(self, mode="ab"):
        return diag.run_diagnostics(self.artifacts, self.app, BUNDLE, OWN, self.runner,
                                    self.pids.append, sleep=lambda _: None, signer=self.signer,
                                    clock=lambda: 0.0, mode=mode)

    def test_signed_positive_uses_one_install_no_a_and_keeps_ui_pending(self):
        # Exercise a noncanonical alias on every OS without symlink privileges.
        self.artifacts = self.artifacts / ".." / self.artifacts.name
        before = (self.app / "BetterZslf").read_bytes()
        value = self.run_probe("signed-positive")
        self.assertEqual(value["experiment_mode"], "signed-positive")
        self.assertEqual(value["unsigned"]["result"], "not_run")
        self.assertIn("historical-negative-only", value["unsigned"]["coverage"])
        self.assertEqual(value["between_phases"]["result"], "not_applicable")
        self.assertFalse(value["login_ui_verified"])
        self.assertEqual(value["ui_review"], "pending")
        self.assertEqual([env["SIMCTL_CHILD_BETTER_ZSLF_STARTUP_PROBE"] for env in self.runner.launches], ["seed", "verify"])
        self.assertEqual(len(set(self.pids)), 2)
        self.assertEqual(self.runner.launches[0]["SIMCTL_CHILD_BETTER_ZSLF_PROBE_NAMESPACE"],
                         self.runner.launches[1]["SIMCTL_CHILD_BETTER_ZSLF_PROBE_NAMESPACE"])
        installs = [args for args, _ in self.runner.calls if args[0] == "install"]
        self.assertEqual(installs, [["install", OWN, str((self.artifacts / "signed" / self.app.name).resolve())]])
        self.assertFalse(any(args[0] == "uninstall" or label.startswith("unsigned") for args, label in self.runner.calls))
        self.assertEqual((self.app / "BetterZslf").read_bytes(), before)
        self.assertTrue(value["signed"]["seed"]["termination"]["confirmed_absent"])

    def test_signed_positive_native_failures_in_either_phase_never_pass(self):
        for phase in ("seed", "verify"):
            for field in ("probeOk", "graphOk", "sessionAbsent"):
                with self.subTest(phase=phase, field=field):
                    self.setUp()
                    self.runner.report_overrides["signed-" + phase + "-launch"] = {field: False}
                    with self.assertRaisesRegex(RuntimeError, "checks failed"):
                        self.run_probe("signed-positive")
                    value = json.loads((self.artifacts / "keychain-diagnostics.json").read_text())
                    self.assertEqual(value["result"], "keychain-diagnostics-failed")
                    self.assertEqual(value["unsigned"]["result"], "not_run")
                    self.assertEqual(len(self.runner.launches), 1 if phase == "seed" else 2)
                    self.assertEqual(sum(args[0] == "install" for args, _ in self.runner.calls), 1)

    def test_signed_positive_unknown_absence_stops_verify(self):
        for failure in ("timeout", "invalid"):
            with self.subTest(failure=failure):
                self.setUp()
                if failure == "timeout":
                    self.runner.timeout_labels.update(("signed-seed-after-terminate", "signed-seed-after-terminate-retry"))
                else:
                    self.runner.process_outputs["signed-seed-after-terminate"] = "PID Status Label\n"
                with self.assertRaises((smoke.subprocess.TimeoutExpired, ValueError)):
                    self.run_probe("signed-positive")
                self.assertNotIn("signed-verify-launch", self.runner.timeouts)
                value = json.loads((self.artifacts / "keychain-diagnostics.json").read_text())
                self.assertFalse(value["signed"]["seed"]["termination"]["confirmed_absent"])
                self.assertFalse(value["signed"]["seed"]["termination"]["process_queries"][-1]["valid_full"])

    def test_signed_positive_known_live_bundle_is_not_launched(self):
        self.runner.process_outputs["signed-seed-before-launch"] = f"PID Status Label\n999 0 UIKitApplication:{BUNDLE}[fixture]\n"
        with self.assertRaisesRegex(RuntimeError, "already alive"):
            self.run_probe("signed-positive")
        self.assertEqual(self.runner.launches, [])

    def test_signed_positive_reused_seed_pid_is_not_cross_process_success(self):
        self.runner.launch_pids["signed-verify-launch"] = 1001
        with self.assertRaisesRegex(RuntimeError, "reused the seed PID"):
            self.run_probe("signed-positive")
        value = json.loads((self.artifacts / "keychain-diagnostics.json").read_text())
        self.assertEqual(value["signed"]["result"], "failed")
        self.assertEqual(len(self.runner.launches), 2)

    def test_terminate_error_is_saved_even_when_valid_table_stays_live(self):
        self.runner.fail_labels.add("check-terminate")
        live = f"PID Status Label\n1001 0 UIKitApplication:{BUNDLE}[fixture]\n"
        for label in ("check-after-terminate", "check-after-terminate-observe-2", "check-after-terminate-observe-3"):
            self.runner.process_outputs[label] = live
        record, snapshots = {}, []
        with self.assertRaisesRegex(RuntimeError, "still alive"):
            diag._terminate_and_confirm(self.runner, OWN, BUNDLE, 1001, "check", record=record,
                                       save=lambda: snapshots.append(copy.deepcopy(record)), sleep=lambda _: None, clock=lambda: 0)
        self.assertFalse(record["confirmed_absent"])
        self.assertEqual(record["terminate_error"]["type"], "RuntimeError")
        self.assertEqual(record["process_query_attempts"], 3)
        self.assertTrue(all(query["valid_full"] and not query["absent"] for query in record["process_queries"]))
        self.assertTrue(any(item.get("terminate_error") and not item["process_queries"] for item in snapshots))

    def test_valid_live_then_valid_absent_needs_no_second_terminate(self):
        self.runner.process_outputs["check-after-terminate"] = f"PID Status Label\n1001 0 UIKitApplication:{BUNDLE}[fixture]\n"
        value = diag._terminate_and_confirm(self.runner, OWN, BUNDLE, 1001, "check", sleep=lambda _: None, clock=lambda: 0)
        self.assertTrue(value["confirmed_absent"])
        self.assertEqual([query["outcome"] for query in value["process_queries"]], ["valid_live", "valid_absent"])
        self.assertEqual(sum(label == "check-terminate" for _, label in self.runner.calls), 1)
        self.assertTrue(all("finished_at_utc" in query for query in value["process_queries"]))

    def test_absence_arriving_after_observation_deadline_does_not_pass(self):
        now, record = [0.0], {}
        original = self.runner.call
        def late(args, label, timeout):
            value = original(args, label, timeout)
            if label == "check-after-terminate":
                now[0] = 15.01
            return value
        with mock.patch.object(self.runner, "call", side_effect=late), self.assertRaises(TimeoutError):
            diag._terminate_and_confirm(self.runner, OWN, BUNDLE, 1001, "check", record=record,
                                       sleep=lambda _: None, clock=lambda: now[0])
        self.assertFalse(record["confirmed_absent"])
        self.assertEqual(record["process_query_attempts"], 1)
        self.assertTrue(record["process_queries"][0]["valid_full"])
        self.assertTrue(record["process_queries"][0]["deadline_exceeded"])

    def test_terminate_error_does_not_erase_independent_valid_absence(self):
        self.runner.fail_labels.add("check-terminate")
        value = diag._terminate_and_confirm(self.runner, OWN, BUNDLE, 1001, "check", sleep=lambda _: None, clock=lambda: 0)
        self.assertTrue(value["confirmed_absent"])
        self.assertEqual(value["terminate_error"]["type"], "RuntimeError")

    def test_watchdog_terminate_is_persisted_and_never_tolerated(self):
        record = {}
        with mock.patch.object(self.runner, "call", side_effect=smoke.SmokeDeadlineExceeded("body expired")) as call:
            with self.assertRaises(smoke.SmokeDeadlineExceeded):
                diag._terminate_and_confirm(self.runner, OWN, BUNDLE, 1001, "check", record=record)
        self.assertEqual(call.call_count, 1)
        self.assertFalse(record["confirmed_absent"])
        self.assertEqual(record["terminate_error"]["type"], "SmokeDeadlineExceeded")
        self.assertEqual(record["process_queries"], [])

    def test_signed_positive_watchdog_never_continues_to_verify_or_extra_capture(self):
        original = self.runner.call
        def expired(args, label, timeout):
            if label == "signed-seed-terminate":
                raise smoke.SmokeDeadlineExceeded("body expired")
            return original(args, label, timeout)
        with mock.patch.object(self.runner, "call", side_effect=expired) as call:
            with self.assertRaises(smoke.SmokeDeadlineExceeded):
                self.run_probe("signed-positive")
        self.assertEqual(call.call_args.args[1], "signed-seed-terminate")
        self.assertEqual(len(self.runner.launches), 1)
        value = json.loads((self.artifacts / "keychain-diagnostics.json").read_text())
        self.assertEqual(value["result"], "keychain-diagnostics-failed")
        self.assertEqual(value["signed"]["seed"]["termination"]["terminate_error"]["type"], "SmokeDeadlineExceeded")

    def test_launch_failure_capture_drain_watchdog_stops_without_later_native_commands(self):
        primary = smoke.subprocess.TimeoutExpired("scoped log", 15, output=b"partial-log")
        process = mock.Mock(pid=12345, returncode=0)
        process.communicate.side_effect = [primary, smoke.SmokeDeadlineExceeded("body expired during drain")]
        self.runner.launch_failure_labels.add("signed-seed-launch")
        original = self.runner.call
        def call(args, label, timeout):
            if label == "signed-seed-launch-system-log":
                self.runner.calls.append((list(args), label))
                return smoke.Simctl(self.artifacts).call(args, label, timeout)
            return original(args, label, timeout)
        with mock.patch.object(self.runner, "call", side_effect=call), \
                mock.patch.object(smoke.subprocess, "Popen", return_value=process), \
                mock.patch.object(smoke.os, "killpg", create=True), \
                mock.patch.object(smoke.signal, "SIGKILL", 9, create=True):
            with self.assertRaises(smoke.subprocess.TimeoutExpired) as raised:
                self.run_probe("signed-positive")
        self.assertIs(raised.exception, primary)
        self.assertTrue(smoke.is_smoke_deadline(primary))
        self.assertEqual(self.runner.calls[-1][1], "signed-seed-launch-system-log")
        self.assertEqual(len(self.runner.launches), 1)
        value = json.loads((self.artifacts / "keychain-diagnostics.json").read_text())
        seed = value["signed"]["seed"]
        self.assertEqual(seed["error"]["message"], "Synthetic launch denied before PID")
        self.assertEqual(seed["failure_launch_log_error"]["type"], "TimeoutExpired")
        self.assertTrue(seed["failure_launch_log_error"]["smoke_deadline_expired"])
        self.assertTrue(seed["failure_launch_log_partial"])
        self.assertNotIn("failure_screenshot_attempts", seed)
        self.assertFalse(any(args[0] == "terminate" for args, _ in self.runner.calls))

    def test_ab_tolerances_do_not_continue_after_drain_watchdog(self):
        for target in ("unsigned-install", "unsigned-launch-system-log", "unsigned-screenshot", "unsigned-terminate"):
            with self.subTest(target=target):
                self.setUp()
                primary = smoke.subprocess.TimeoutExpired(target, 15, output=b"partial")
                process = mock.Mock(pid=12345, returncode=0)
                process.communicate.side_effect = [primary, smoke.SmokeDeadlineExceeded("body expired during drain")]
                if target == "unsigned-launch-system-log":
                    self.runner.launch_failure_labels.add("unsigned-launch")
                original = self.runner.call
                def call(args, label, timeout):
                    if label == target:
                        self.runner.calls.append((list(args), label))
                        return smoke.Simctl(self.artifacts).call(args, label, timeout)
                    return original(args, label, timeout)
                with mock.patch.object(self.runner, "call", side_effect=call), \
                        mock.patch.object(smoke.subprocess, "Popen", return_value=process), \
                        mock.patch.object(smoke.os, "killpg", create=True), \
                        mock.patch.object(smoke.signal, "SIGKILL", 9, create=True):
                    with self.assertRaises(smoke.subprocess.TimeoutExpired) as raised:
                        self.run_probe()
                self.assertIs(raised.exception, primary)
                self.assertEqual(self.runner.calls[-1][1], target)
                value = json.loads((self.artifacts / "keychain-diagnostics.json").read_text())
                self.assertEqual(value["result"], "keychain-diagnostics-failed")
                self.assertEqual(value["signed"]["result"], "blocked")
                self.assertTrue(value["error"]["smoke_deadline_expired"])
                self.assertFalse(any(label == "signed-install" for _, label in self.runner.calls))

    def test_failure_report_and_screenshot_tolerances_preserve_marked_deadline(self):
        for stage in ("report", "screenshot"):
            with self.subTest(stage=stage):
                self.setUp()
                primary = smoke.subprocess.TimeoutExpired(stage, 15)
                primary.smoke_deadline_expired = True
                record = {"error": {"type": "RuntimeError", "message": "original operation"}}
                snapshots = []
                original = self.runner.call
                def call(args, label, timeout):
                    if label == "check-failure-screenshot":
                        self.runner.calls.append((list(args), label))
                        raise primary
                    return original(args, label, timeout)
                with mock.patch.object(self.runner, "call", side_effect=call), \
                        mock.patch.object(diag, "read_native_report", side_effect=primary) as reader:
                    with self.assertRaises(smoke.subprocess.TimeoutExpired) as raised:
                        diag._capture_failure(self.artifacts, self.runner, OWN, "check", record,
                                              self.container if stage == "report" else None, "seed",
                                              lambda: snapshots.append(copy.deepcopy(record)), BUNDLE)
                self.assertIs(raised.exception, primary)
                self.assertEqual(record["error"]["message"], "original operation")
                self.assertTrue(snapshots[-1]["failure_" + stage + "_error"]["smoke_deadline_expired"])
                if stage == "report":
                    self.assertFalse(any(args[0] == "io" for args, _ in self.runner.calls))
                else:
                    self.assertEqual(len(record["failure_screenshot_attempts"]), 1)

    def test_codesign_result_keeps_nonzero_and_partial_timeout(self):
        command = ["codesign", "--verify", "synthetic-copy"]
        failing = mock.Mock(return_value=mock.Mock(returncode=9, stdout=b"out", stderr=b"err"))
        with self.assertRaises(RuntimeError):
            diag._codesign(command, self.artifacts, "nonzero-sign", failing)
        value = json.loads((self.artifacts / "nonzero-sign.result.json").read_text())
        self.assertEqual(value["outcome"], "nonzero")
        self.assertEqual(value["returncode"], 9)
        self.assertTrue(value["drain_complete"])
        timeout = mock.Mock(side_effect=smoke.subprocess.TimeoutExpired(command, 60, output=b"partial"))
        with self.assertRaises(smoke.subprocess.TimeoutExpired):
            diag._codesign(command, self.artifacts, "timeout-sign", timeout)
        value = json.loads((self.artifacts / "timeout-sign.result.json").read_text())
        self.assertEqual(value["outcome"], "timeout")
        self.assertTrue(value["partial"])
        self.assertTrue(value["timed_out"])
        self.assertEqual(value["stdout_bytes"], 7)

    def test_parent_explicit_positive_mode_is_owned_and_ui_review_stays_pending(self):
        sys.path.insert(0, str(ROOT / "scripts/tests"))
        from test_ios_simulator_smoke import FakeSimctl
        runner = FakeSimctl()
        with mock.patch.object(diag, "run_diagnostics", return_value={"pid": 4321}) as probe:
            value = smoke.smoke(self.artifacts, self.app, runner, sleep=lambda _: None,
                                keychain_diagnostics=True, diagnostic_mode="signed-positive")
        self.assertEqual(probe.call_args.kwargs["mode"], "signed-positive")
        self.assertEqual(value["experiment_mode"], "signed-positive")
        self.assertFalse(value["login_ui_verified"])
        self.assertEqual(value["ui_review"], "pending")
        self.assertIn("A: not_run", (self.artifacts / "README.md").read_text())
        owner = json.loads((self.artifacts / "owner.json").read_text())
        self.assertTrue(owner["creation_confirmed"])
        self.assertEqual(owner["diagnostic_mode"], "signed-positive")
        self.assertEqual(sum(args[0] == "create" for args, _, _ in runner.calls), 1)

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
        self.assertEqual(self.runner.timeouts["signed-seed-after-terminate"], 15)
        self.assertTrue(all(args[1] == OWN for args, _ in self.runner.calls))
        self.assertFalse((self.artifacts / "startup.png").exists())  # Parent takes final stability screenshot.

    def test_screenshot_timeout_recovers_to_distinct_file_without_losing_attempt_evidence(self):
        self.runner.timeout_labels.add("unsigned-screenshot")
        value = self.run_probe()
        unsigned = value["unsigned"]
        self.assertEqual(unsigned["screenshot"], "unsigned-startup-attempt-2.png")
        self.assertEqual([entry["result"] for entry in unsigned["screenshot_attempts"]], ["timeout", "passed"])
        self.assertEqual(unsigned["screenshot_attempts"][0]["error"]["type"], "TimeoutExpired")
        self.assertEqual(unsigned["evidence_errors"], [])
        for name in ("unsigned-startup.png", "unsigned-startup-attempt-2.png"):
            self.assertTrue((self.artifacts / name).is_file())
        for label in ("unsigned-screenshot", "unsigned-screenshot-attempt-2"):
            self.assertEqual(self.runner.timeouts[label], 15)
        action = value["between_phases"]["actions"][0]
        self.assertEqual(action["result"], "passed")
        self.assertEqual(action["screenshot"], unsigned["screenshot"])
        self.assertEqual(len(self.runner.launches), 3)  # Retry only io screenshot, never launch.

    def test_both_screenshot_timeouts_leave_pngs_but_block_b_and_export(self):
        self.runner.timeout_labels.update(("unsigned-screenshot", "unsigned-screenshot-attempt-2"))
        with self.assertRaisesRegex(RuntimeError, "Unsigned baseline"):
            self.run_probe()
        value = json.loads((self.artifacts / "keychain-diagnostics.json").read_text())
        self.assertEqual([entry["result"] for entry in value["unsigned"]["screenshot_attempts"]], ["timeout", "timeout"])
        self.assertNotIn("screenshot", value["unsigned"])
        self.assertTrue((self.artifacts / "unsigned-startup.png").is_file())
        self.assertTrue((self.artifacts / "unsigned-startup-attempt-2.png").is_file())
        self.assertEqual(value["between_phases"]["actions"][0]["result"], "failed")
        self.assertIsNone(value["between_phases"]["actions"][0]["screenshot"])
        self.assertEqual(value["signed"]["result"], "blocked")
        self.assertFalse(any(args[0] == "uninstall" for args, _ in self.runner.calls))

    def test_nonzero_or_bad_png_is_not_a_retryable_success(self):
        for failure in ("nonzero", "bad-png"):
            with self.subTest(failure=failure):
                self.runner.calls.clear()
                self.runner.timeouts.clear()
                self.runner.fail_labels.clear()
                self.runner.screenshot_payloads.clear()
                if failure == "nonzero":
                    self.runner.fail_labels.add("unsigned-screenshot")
                else:
                    self.runner.screenshot_payloads["unsigned-screenshot"] = b"not a PNG"
                with self.assertRaisesRegex(RuntimeError, "Unsigned baseline"):
                    self.run_probe()
                self.assertNotIn("unsigned-screenshot-attempt-2", self.runner.timeouts)
                value = json.loads((self.artifacts / "keychain-diagnostics.json").read_text())
                self.assertEqual(value["unsigned"]["screenshot_attempts"][0]["result"], "failed")
                self.assertEqual(value["between_phases"]["actions"][0]["result"], "failed")
                self.assertEqual(value["signed"]["result"], "blocked")

    def test_clean_reinstall_exports_a_before_one_owned_uninstall_and_keeps_b_install(self):
        value = self.run_probe()
        labels = [label for _, label in self.runner.calls]
        self.assertLess(labels.index("unsigned-screenshot"), labels.index("between-uninstall-unsigned"))
        self.assertLess(labels.index("unsigned-after-terminate"), labels.index("between-uninstall-unsigned"))
        self.assertLess(labels.index("between-uninstall-unsigned"), labels.index("signed-install"))
        self.assertLess(labels.index("signed-install"), labels.index("signed-seed-launch"))
        self.assertLess(labels.index("signed-seed-after-terminate"), labels.index("signed-verify-launch"))
        self.assertEqual([args for args, _ in self.runner.calls if args[0] == "uninstall"], [["uninstall", OWN, BUNDLE]])
        self.assertEqual(sum(args[0] == "install" for args, _ in self.runner.calls), 2)
        self.assertFalse(self.container.exists())  # The old container really disappeared in the fixture.
        self.assertTrue((self.artifacts / "unsigned-startup-report.json").is_file())
        self.assertEqual(smoke.png_dimensions(self.artifacts / "unsigned-startup.png"), (390, 844))
        transition = value["between_phases"]
        self.assertEqual(transition["result"], "completed")
        self.assertIn("not an established", transition["interpretation"])
        self.assertEqual([entry["action"] for entry in transition["actions"]],
                         ["export-unsigned-evidence", "confirm-unsigned-absence", "uninstall-unsigned-owned-app", "install-signed-copy"])
        for entry in transition["actions"][2:]:
            self.assertIn("started_at_utc", entry)
            self.assertIn("finished_at_utc", entry)

    def test_pid_query_timeout_recovers_once_without_second_terminate_or_launch(self):
        self.runner.timeout_labels.add("unsigned-after-terminate")
        value = self.run_probe()
        self.assertEqual(value["unsigned"]["termination"]["process_query_attempts"], 2)
        self.assertEqual(self.runner.timeouts["unsigned-after-terminate"], 15)
        self.assertEqual(self.runner.timeouts["unsigned-after-terminate-retry"], 15)
        self.assertEqual(sum(label == "unsigned-terminate" for _, label in self.runner.calls), 1)
        self.assertEqual(len(self.runner.launches), 3)

    def test_exhausted_pid_query_blocks_b_before_uninstall_or_sign(self):
        self.runner.timeout_labels.update(("unsigned-after-terminate", "unsigned-after-terminate-retry"))
        with self.assertRaisesRegex(RuntimeError, "absence is unconfirmed"):
            self.run_probe()
        value = json.loads((self.artifacts / "keychain-diagnostics.json").read_text())
        self.assertEqual(value["signed"]["result"], "blocked")
        self.assertEqual(value["between_phases"]["result"], "blocked")
        self.assertFalse(any(args[0] == "uninstall" for args, _ in self.runner.calls))
        self.assertFalse((self.artifacts / "signed").exists())
        self.assertEqual(len(self.runner.launches), 1)

    def test_empty_partial_header_or_still_live_bundle_never_proves_absence(self):
        for output in ("", "PID\tStatus\tLabel\n", "PID\tStatus\tLabel\n- 0 com.apple.fixture",
                       "PID\tStatus\tLabel\n- 0 com.apple.fixture\n1234 0\n",
                       f"PID\tStatus\tLabel\n2222 0 UIKitApplication:{BUNDLE}[fixture]\n"):
            with self.subTest(output=output):
                for label in ("check-after-terminate", "check-after-terminate-observe-2", "check-after-terminate-observe-3"):
                    self.runner.process_outputs[label] = output
                with self.assertRaises((ValueError, RuntimeError)):
                    diag._terminate_and_confirm(self.runner, OWN, BUNDLE, 1001, "check")
                self.assertNotIn("check-after-terminate-retry", self.runner.timeouts)

    def test_uninstall_failure_is_not_retried_and_never_installs_signed_copy(self):
        self.runner.fail_labels.add("between-uninstall-unsigned")
        with self.assertRaisesRegex(RuntimeError, "Synthetic command failure"):
            self.run_probe()
        self.assertEqual(sum(args[0] == "uninstall" for args, _ in self.runner.calls), 1)
        self.assertNotIn("signed-install", self.runner.timeouts)
        value = json.loads((self.artifacts / "keychain-diagnostics.json").read_text())
        self.assertEqual(value["between_phases"]["result"], "failed")
        self.assertTrue((self.artifacts / "unsigned-startup-report.json").is_file())

    def test_seed_absence_failure_blocks_verify_without_another_uninstall(self):
        self.runner.timeout_labels.update(("signed-seed-after-terminate", "signed-seed-after-terminate-retry"))
        with self.assertRaises(smoke.subprocess.TimeoutExpired):
            self.run_probe()
        self.assertNotIn("signed-verify-launch", self.runner.timeouts)
        self.assertEqual(len(self.runner.launches), 2)
        self.assertEqual(sum(args[0] == "uninstall" for args, _ in self.runner.calls), 1)
        labels = [label for _, label in self.runner.calls]
        self.assertEqual(labels[labels.index("signed-seed-after-terminate-retry") + 1], "signed-seed-launch-system-log")

    def test_log_timeout_is_partial_and_keeps_original_denial_before_screenshot(self):
        self.runner.launch_failure_labels.add("signed-seed-launch")
        self.runner.timeout_labels.add("signed-seed-launch-system-log")
        with self.assertRaisesRegex(RuntimeError, "Synthetic launch denied"):
            self.run_probe()
        value = json.loads((self.artifacts / "keychain-diagnostics.json").read_text())
        seed = value["signed"]["seed"]
        self.assertFalse(seed["failure_launch_log_complete"])
        self.assertTrue(seed["failure_launch_log_partial"])
        self.assertEqual(seed["failure_launch_log_window_seconds"], 30)
        self.assertEqual(seed["failure_launch_log_error"]["type"], "TimeoutExpired")
        for field in ("launch_started_at_utc", "launch_finished_at_utc", "failure_observed_at_utc",
                      "failure_launch_log_started_at_utc", "failure_launch_log_finished_at_utc"):
            self.assertIn(field, seed)
        labels = [label for _, label in self.runner.calls]
        self.assertLess(labels.index("signed-seed-launch-system-log"), labels.index("signed-seed-failure-screenshot"))
        self.assertEqual(value["error"]["message"], "Synthetic launch denied before PID")

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
        self.assertEqual(value["signed"]["result"], "blocked")
        self.assertEqual(value["result"], "keychain-diagnostics-failed")
        self.assertEqual(len(self.runner.launches), 0)
        self.assertFalse(any(args[0] == "uninstall" for args, _ in self.runner.calls))

    def test_launch_denial_gets_scoped_logs_without_relaunch(self):
        self.runner.launch_failure_labels.add("signed-seed-launch")
        with self.assertRaisesRegex(RuntimeError, "Synthetic launch denied"):
            self.run_probe()
        self.assertEqual(len(self.runner.launches), 2)
        logs = [(args, label) for args, label in self.runner.calls if label == "signed-seed-launch-system-log"]
        self.assertEqual(len(logs), 1)
        args = logs[0][0]
        self.assertEqual(args[:3], ["spawn", OWN, "log"])
        self.assertEqual(args[args.index("--last") + 1], "30s")
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
        self.assertEqual(len(self.runner.launches), 1)
        value = json.loads((self.artifacts / "keychain-diagnostics.json").read_text())
        self.assertEqual(value["result"], "keychain-diagnostics-failed")
        self.assertEqual(value["signed"]["result"], "blocked")
        self.assertFalse(any(args[0] == "uninstall" for args, _ in self.runner.calls))

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
        self.assertNotEqual(target, self.app.resolve())
        self.assertTrue(all(str(self.app.resolve()) not in c for c in calls))
        self.assertIn("-", next(c for c in calls if "--sign" in c))
        with (target.parent / "CI-simulator-entitlements.plist").open("rb") as stream:
            entitlements = plistlib.load(stream)
        self.assertNotIn("keychain-access-groups", entitlements)
        self.assertEqual(entitlements, {"application-identifier": "CIKEYCHAIN." + BUNDLE,
                                        "com.apple.developer.team-identifier": "CIKEYCHAIN", "get-task-allow": True})
        evidence = json.loads((target.parent / "signing.json").read_text())
        self.assertEqual(evidence["profile"], "simulator-single-app")
        self.assertIs(evidence["keychain_sharing"], False)

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
