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


def fixture_der(value):
    def tlv(tag, body):
        size = bytes([len(body)]) if len(body) < 128 else bytes([0x82]) + len(body).to_bytes(2, "big")
        return bytes([tag]) + size + body
    def scalar(item):
        if type(item) is bool:
            return tlv(1, b"\xff" if item else b"\0")
        if type(item) is int:
            return tlv(2, item.to_bytes((item.bit_length() + 8) // 8, "big", signed=True))
        if type(item) is dict:
            return tlv(0xb0, b"".join(tlv(0x30, scalar(key) + scalar(child)) for key, child in sorted(item.items())))
        if type(item) is list:
            return tlv(0x30, b"".join(scalar(child) for child in item))
        return tlv(12, item.encode("utf8"))
    return tlv(0x70, tlv(2, b"\x01") + scalar(value))


def packaged_fixture(*, simulated=True, real=None, mismatch=False, identity=None, omit_der=False,
                     sim_extra=None, sim_der_extra=None, real_der=None):
    """Non-executable minimal Mach-O64 structures, never a runnable native program."""
    value = {"application-identifier": identity or "CIKEYCHAIN." + BUNDLE,
             "com.apple.developer.team-identifier": "CIKEYCHAIN", "get-task-allow": True}
    value.update(sim_extra or {})
    der_value = {**value, **(sim_der_extra or {})}
    sections = []
    if simulated:
        sections = [("__entitlements", plistlib.dumps(value)),
                    ("__ents_der", fixture_der({**der_value, "get-task-allow": False} if mismatch else der_value))]
        if omit_der:
            sections = sections[:1]
    command_size = 72 + 80 * len(sections) + 16
    offset = 32 + command_size
    body, records = b"", b""
    for name, data in sections:
        records += struct.pack("<16s16sQQIIIIIIII", name.encode(), b"__TEXT", offset, len(data), offset, 0, 0, 0, 0, 0, 0, 0)
        body += data
        offset += len(data)
    directory = bytearray(52)
    identifier = BUNDLE.encode() + b"\0"
    struct.pack_into(">IIIIII", directory, 0, 0xfade0c02, len(directory) + len(identifier), 0x20400, 2, 0, 52)
    directory += identifier
    blobs = [(0, bytes(directory))]
    if real is not None:
        for slot, magic, data in ((5, 0xfade7171, plistlib.dumps(real)),
                                  (7, 0xfade7172, fixture_der(real if real_der is None else real_der))):
            blobs.append((slot, struct.pack(">II", magic, 8 + len(data)) + data))
    head_size = 12 + len(blobs) * 8
    indexes, payload = b"", b""
    for slot, blob in blobs:
        indexes += struct.pack(">II", slot, head_size + len(payload))
        payload += blob
    signature = struct.pack(">III", 0xfade0cc0, head_size + len(payload), len(blobs)) + indexes + payload
    segment = bytearray(72)
    struct.pack_into("<II16s", segment, 0, 0x19, 72 + len(records), b"__TEXT")
    struct.pack_into("<I", segment, 64, len(sections))
    sigcmd = struct.pack("<IIII", 0x1d, 16, offset, len(signature))
    header = struct.pack("<IIIIIIII", 0xfeedfacf, 0x100000c, 0, 2, 2, command_size, 0, 0)
    return header + segment + records + sigcmd + body + signature


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
        self.launch_errors = {}
        self.host_calls = []
        self.host_outputs = {"ps": "1 0 /sbin/launchd\n", "vm_stat": "Mach Virtual Memory Statistics: fixture\n"}

    def call_host(self, kind, label, timeout):
        self.host_calls.append((kind, label, timeout))
        return self.host_outputs[kind]

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
        if label in self.launch_errors:
            raise self.launch_errors[label]
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

    def packaged_app(self, **kwargs):
        (self.app / "BetterZslf").write_bytes(packaged_fixture(**kwargs))

    def xcode_probe(self, run=None):
        run = run or mock.Mock(return_value=mock.Mock(returncode=0, stdout=b"fixture-details", stderr=b"fixture-requirements"))
        with mock.patch.object(diag.sys, "platform", "darwin"), mock.patch.object(diag.subprocess, "run", run), \
                mock.patch.object(diag.shutil, "which", return_value="/fixture/codesign"):
            return diag.run_diagnostics(self.artifacts, self.app, BUNDLE, OWN, self.runner,
                                        self.pids.append, sleep=lambda _: None, clock=lambda: 0,
                                        mode="signed-positive", signing_method="xcode-simulator")

    def observe_failure(self, record=None, clock=lambda: 0.0):
        record = {} if record is None else record
        diag._observe_failed_launch(self.artifacts, self.runner, OWN, "check", record, self.container,
                                    "seed", lambda: None, BUNDLE, "BetterZslf", self.app.name, clock)
        return record

    def test_launch_timeout_observes_report_and_pid_without_upgrading_or_verify(self):
        primary = smoke.subprocess.TimeoutExpired("fixture launch", 30)
        original = self.runner.call_env
        def launched_but_cli_timed_out(*args, **kwargs):
            original(*args, **kwargs)
            raise primary
        path = f"/Users/runner/Library/Developer/CoreSimulator/Devices/{OWN}/data/Containers/Bundle/Application/{OWN}/BetterZslf.app/BetterZslf"
        self.runner.host_outputs["ps"] = f"1001 1 {path}\n1 0 /sbin/launchd\n"
        events = []
        reader, call = diag.read_native_report, self.runner.call
        def read(*args):
            events.append("native")
            return reader(*args)
        def invoked(args, label, timeout):
            events.append(label)
            return call(args, label, timeout)
        with mock.patch.object(self.runner, "call_env", side_effect=launched_but_cli_timed_out), \
                mock.patch.object(self.runner, "call", side_effect=invoked), \
                mock.patch.object(diag, "read_native_report", side_effect=read):
            with self.assertRaises(smoke.subprocess.TimeoutExpired) as raised:
                self.run_probe("signed-positive")
        self.assertIs(raised.exception, primary)
        value = json.loads((self.artifacts / "keychain-diagnostics.json").read_text())
        seed = value["signed"]["seed"]
        self.assertEqual(seed["result"], "failed")
        self.assertTrue(seed["report"]["probeOk"])
        self.assertIsNone(value["pid"])
        self.assertNotIn("pid", seed)
        self.assertEqual(value["signed"]["verify"], {})
        self.assertEqual(self.pids, [])
        self.assertEqual(len(self.runner.launches), 1)
        self.assertEqual(sum(args[0] == "install" for args, _ in self.runner.calls), 1)
        steps = seed["failure_observations"]["steps"]
        self.assertEqual([s["kind"] for s in steps], ["early_native_report", "launchctl", "host_ps_comm", "host_vm_stat"])
        self.assertEqual(steps[1]["observed_bundle_pids"], [1001])
        self.assertEqual(steps[2]["observed_candidates"][0]["pid"], 1001)
        self.assertLess(events.index("native"), events.index("signed-seed-failure-processes"))
        self.assertLess(events.index("signed-seed-failure-processes"), events.index("signed-seed-launch-system-log"))
        self.assertTrue(all(0 < seconds <= 3 for _, _, seconds in self.runner.host_calls))

    def test_early_report_valid_invalid_missing_never_requeries_container(self):
        for state in ("valid", "invalid", "missing"):
            with self.subTest(state=state):
                self.setUp()
                path = self.container / diag.REPORT_RELATIVE
                if state != "missing":
                    path.parent.mkdir(parents=True)
                    path.write_text(json.dumps(report("seed", True) if state == "valid" else {"schema": 1}), encoding="utf8")
                record = self.observe_failure()
                entry = record["failure_observations"]["steps"][0]
                self.assertEqual(entry["outcome"], "observed_report" if state == "valid" else "unknown")
                self.assertEqual("report" in record, state == "valid")
                self.assertFalse(any(args[0] == "get_app_container" for args, _ in self.runner.calls))
                if state == "invalid":
                    self.assertEqual(entry["error"]["type"], "ReportError")

    def test_failure_process_snapshot_is_single_strict_and_never_verified_pid(self):
        for state in ("valid", "bad", "timeout"):
            with self.subTest(state=state):
                self.setUp()
                if state == "valid":
                    self.runner.pid = 1234
                elif state == "bad":
                    self.runner.process_outputs["check-failure-processes"] = "PID Status Label\ntruncated"
                else:
                    self.runner.timeout_labels.add("check-failure-processes")
                record = self.observe_failure()
                item = record["failure_observations"]["steps"][1]
                self.assertEqual(item["outcome"], "observed" if state == "valid" else "unknown")
                self.assertNotIn("pid", record)
                calls = [(args, label) for args, label in self.runner.calls if label == "check-failure-processes"]
                self.assertEqual(len(calls), 1)
                self.assertLessEqual(self.runner.timeouts[calls[0][1]], 3)
                if state == "valid":
                    self.assertEqual(item["observed_bundle_pids"], [1234])
                else:
                    self.assertNotIn("observed_bundle_pids", item)

    def test_failure_observation_shared_15s_and_existing_600s_deadline(self):
        for source in ("local15", "body600", "body_short"):
            with self.subTest(source=source):
                self.setUp()
                now = [0.0]
                if source == "body600":
                    self.runner.body_deadline_monotonic = 0.0
                elif source == "body_short":
                    self.runner.body_deadline_monotonic = 1.0
                original = self.runner.call
                def late(args, label, timeout):
                    value = original(args, label, timeout)
                    now[0] = 1.1 if source == "body_short" else 15.1
                    return value
                with mock.patch.object(self.runner, "call", side_effect=late), \
                        mock.patch.object(diag, "read_native_report", wraps=diag.read_native_report) as reader:
                    record = self.observe_failure(clock=lambda: now[0])
                data = record["failure_observations"]
                self.assertTrue(data["budget_exhausted"])
                self.assertEqual(self.runner.host_calls, [])
                if source == "body600":
                    self.assertTrue(data["body_deadline_exhausted"])
                    reader.assert_not_called()
                    self.assertEqual(self.runner.calls, [])
                    self.assertTrue(all(item["outcome"] == "skipped_budget" for item in data["steps"]))
                else:
                    self.assertEqual(data["steps"][1]["outcome"], "late_unknown")
                    self.assertNotIn("valid_full", data["steps"][1])
                    self.assertEqual(len(self.runner.calls), 1)
                    if source == "body_short":
                        self.assertEqual(self.runner.timeouts["check-failure-processes"], 0.5)
                        self.assertTrue(data["body_deadline_exhausted"])

    def test_host_ps_requires_full_rows_exact_udid_container_and_executable(self):
        path = f"/Users/runner/Library/Developer/CoreSimulator/Devices/{OWN}/data/Containers/Bundle/Application/{OWN}/BetterZslf.app/BetterZslf"
        parsed = diag.parse_host_ps_candidates(f"12 1 {path}\n", OWN, "BetterZslf", self.app.name)
        self.assertEqual(parsed["observed_candidates"][0]["pid"], 12)
        for comm in ("BetterZslf", "/usr/libexec/xpcproxy", path + " --argv", path + ".debug.dylib",
                     path.replace("Devices/" + OWN, "Devices/AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"),
                     path.replace("Application/" + OWN, "Application/" + "-" * 36),
                     path.replace("/BetterZslf.app/", "/../BetterZslf.app/")):
            with self.subTest(comm=comm):
                self.assertEqual(diag.parse_host_ps_candidates(f"12 1 {comm}\n", OWN, "BetterZslf", self.app.name)["observed_candidates"], [])
        for raw in ("12 1 BetterZslf", "12 noPID BetterZslf\n", "12 1 x\n12 1 y\n", "12 1 x\ntruncated\n"):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                diag.parse_host_ps_candidates(raw, OWN, "BetterZslf", self.app.name)

    def test_launch_primary_identity_survives_host_and_summary_io_errors(self):
        primary = smoke.subprocess.TimeoutExpired("fixture launch", 30)
        self.runner.launch_errors["signed-seed-launch"] = primary
        original = diag._save_json
        def failing_final_save(path, value):
            if value.get("signed", {}).get("seed", {}).get("launch_command_failed"):
                raise OSError("fixture summary write failure")
            return original(path, value)
        with mock.patch.object(diag, "_save_json", side_effect=failing_final_save), \
                mock.patch.object(self.runner, "call_host", side_effect=OSError("fixture host IO")):
            with self.assertRaises(smoke.subprocess.TimeoutExpired) as raised:
                self.run_probe("signed-positive")
        self.assertIs(raised.exception, primary)
        self.assertEqual(len(self.runner.launches), 1)
        self.assertFalse(any(label == "signed-verify-launch" for _, label in self.runner.calls))

    def test_secondary_watchdog_marks_original_launch_exception_and_stops_capture(self):
        primary = smoke.subprocess.TimeoutExpired("fixture launch", 30)
        self.runner.launch_errors["signed-seed-launch"] = primary
        original = self.runner.call
        def expired(args, label, timeout):
            if label == "signed-seed-failure-processes":
                self.runner.calls.append((list(args), label))
                raise smoke.SmokeDeadlineExceeded("fixture remaining body expired")
            return original(args, label, timeout)
        with mock.patch.object(self.runner, "call", side_effect=expired):
            with self.assertRaises(smoke.subprocess.TimeoutExpired) as raised:
                self.run_probe("signed-positive")
        self.assertIs(raised.exception, primary)
        self.assertTrue(smoke.is_smoke_deadline(primary))
        self.assertEqual(self.runner.host_calls, [])
        self.assertEqual(self.runner.calls[-1][1], "signed-seed-failure-processes")
        value = json.loads((self.artifacts / "keychain-diagnostics.json").read_text())
        self.assertEqual(value["result"], "keychain-diagnostics-failed")
        self.assertEqual(value["signed"]["verify"], {})

    def test_success_path_has_no_new_host_observation(self):
        self.assertEqual(self.run_probe("signed-positive")["result"], "keychain-diagnostics-passed")
        self.assertEqual(self.runner.host_calls, [])
        self.assertFalse(any("failure-processes" in label for _, label in self.runner.calls))

    def test_xcode_packaged_copy_audits_sections_and_never_resigns(self):
        self.packaged_app(real={"get-task-allow": True})
        (self.app / "BetterZslf.debug.dylib").write_bytes(packaged_fixture(simulated=False))
        before = diag.file_sha256(self.app / "BetterZslf")
        run = mock.Mock(return_value=mock.Mock(returncode=0, stdout=b"fixture", stderr=b"fixture"))
        with mock.patch.object(diag.sys, "platform", "darwin"), \
                mock.patch.object(diag.shutil, "which", return_value="/fixture/codesign"):
            copy = diag.prepare_xcode_simulator_copy(self.artifacts, self.app, BUNDLE, run=run)
        self.assertEqual(diag.file_sha256(copy / "BetterZslf"), before)
        self.assertEqual(diag.file_sha256(self.app / "BetterZslf"), before)
        self.assertTrue(all("--sign" not in call.args[0] for call in run.call_args_list))
        self.assertTrue(all(call.kwargs["timeout"] == 15 for call in run.call_args_list))
        meta = json.loads((self.artifacts / "signed/signing.json").read_text())
        self.assertEqual(meta["profile"], "xcode-simulator-packaged")
        self.assertTrue(meta["source_unchanged"])
        self.assertEqual(meta["images"][0]["simulated_xml"], meta["images"][0]["simulated_der"])
        self.assertEqual(meta["images"][0]["signature_xml"], {"get-task-allow": True})

    def test_xcode_missing_unknown_mismatch_or_real_claim_stops_before_install(self):
        cases = ({"simulated": False}, {"omit_der": True}, {"mismatch": True}, {"identity": "wrong.application"},
                 {"real": {"application-identifier": "CIKEYCHAIN." + BUNDLE}},
                 {"real": {"com.apple.developer.team-identifier": "CIKEYCHAIN"}},
                 {"real": {"keychain-access-groups": "unexpected-group"}})
        for fields in cases:
            with self.subTest(fields=fields):
                self.setUp()
                self.packaged_app(**fields)
                with self.assertRaises(ValueError):
                    self.xcode_probe()
                self.assertFalse(any(args[0] == "install" for args, _ in self.runner.calls))
                meta = json.loads((self.artifacts / "signed/signing.json").read_text())
                self.assertEqual(meta["result"], "failed")
                self.assertEqual(self.runner.launches, [])

    def test_entitlement_canonical_is_nested_type_exact_and_rejects_unknown_types(self):
        boolean = {"nested": [{"flag": True}]}
        integer = {"nested": [{"flag": 1}]}
        self.assertEqual(boolean, integer)  # Reproduce Python's loose comparison, not the evidence gate.
        self.assertNotEqual(diag.canonical_entitlements(boolean), diag.canonical_entitlements(integer))
        self.assertEqual(diag.canonical_entitlements({"b": 1, "a": boolean}),
                         diag.canonical_entitlements({"a": boolean, "b": 1}))
        for value in ({"nested": [None]}, {"nested": [1.0]}, {"data": b"unsupported"}, {1: "key"}):
            with self.subTest(value=value), self.assertRaises(ValueError):
                diag.canonical_entitlements(value)
        with self.assertRaises(ValueError):
            diag.decode_entitlement_der(fixture_der({}).replace(b"\x02\x01\x01", b"\x01\x01\xff", 1))

    def test_nested_bool_int_mismatch_blocks_source_copy_and_native_install(self):
        boolean = {"nested": [{"flag": True}]}
        integer = {"nested": [{"flag": 1}]}
        for stage in ("signature", "simulated", "cross-image", "copy-signature", "copy-simulated"):
            with self.subTest(stage=stage):
                self.setUp()
                fields = {"sim_extra": boolean}
                if stage == "signature":
                    fields.update(real=boolean, real_der=integer)
                elif stage == "simulated":
                    fields["sim_der_extra"] = integer
                self.packaged_app(**fields)
                if stage == "cross-image":
                    (self.app / "BetterZslf.debug.dylib").write_bytes(packaged_fixture(sim_extra=integer))
                original_copy = diag.shutil.copytree
                def copied(source, target, **kwargs):
                    result = original_copy(source, target, **kwargs)
                    if stage == "copy-signature":
                        (Path(target) / "BetterZslf").write_bytes(packaged_fixture(real=boolean, real_der=integer))
                    elif stage == "copy-simulated":
                        (Path(target) / "BetterZslf").write_bytes(packaged_fixture(sim_extra=boolean, sim_der_extra=integer))
                    return result
                with mock.patch.object(diag.shutil, "copytree", side_effect=copied), self.assertRaises(ValueError):
                    self.xcode_probe()
                self.assertFalse(any(args[0] == "install" for args, _ in self.runner.calls))
                self.assertEqual(self.runner.launches, [])
                meta = json.loads((self.artifacts / "signed/signing.json").read_text())
                self.assertEqual(meta["result"], "failed")

    def test_xcode_verify_nonzero_is_not_installable(self):
        self.packaged_app()
        def run(command, **_):
            return mock.Mock(returncode=7 if "--verify" in command else 0, stdout=b"fixture", stderr=b"fixture")
        with self.assertRaises(RuntimeError):
            self.xcode_probe(run)
        self.assertFalse(any(args[0] == "install" for args, _ in self.runner.calls))
        result = json.loads((self.artifacts / "signed/xcode-verify.result.json").read_text())
        self.assertEqual(result["returncode"], 7)

    def test_xcode_method_keeps_one_install_two_real_report_phases_and_ui_pending(self):
        self.packaged_app()
        value = self.xcode_probe()
        self.assertEqual(value["signing_method"], "xcode-simulator")
        self.assertEqual(value["unsigned"]["result"], "not_run")
        self.assertEqual(len(self.runner.launches), 2)
        self.assertEqual(len(set(self.pids)), 2)
        self.assertEqual(sum(args[0] == "install" for args, _ in self.runner.calls), 1)
        self.assertFalse(any(args[0] == "uninstall" for args, _ in self.runner.calls))
        self.assertTrue(value["signed"]["seed"]["termination"]["confirmed_absent"])
        self.assertFalse(value["login_ui_verified"])

    def test_xcode_method_native_failure_in_either_phase_is_still_fatal(self):
        for phase in ("seed", "verify"):
            with self.subTest(phase=phase):
                self.setUp()
                self.packaged_app()
                self.runner.report_overrides["signed-" + phase + "-launch"] = {"graphOk": False}
                with self.assertRaisesRegex(RuntimeError, "checks failed"):
                    self.xcode_probe()
                self.assertEqual(len(self.runner.launches), 1 if phase == "seed" else 2)
                self.assertEqual(sum(args[0] == "install" for args, _ in self.runner.calls), 1)

    def test_der_decoder_is_bounded_and_preserves_false(self):
        self.assertEqual(diag.decode_entitlement_der(fixture_der({"get-task-allow": False})), {"get-task-allow": False})
        for value in (b"", b"\x70\x80", b"\x30\x00", b"\x70\x7f", b"x" * (diag.MAX_REPORT_BYTES + 1)):
            with self.subTest(value=value[:8]), self.assertRaises(ValueError):
                diag.decode_entitlement_der(value)

    def test_layout_parser_rejects_huge_commands_and_retains_failure_record(self):
        path = self.app / "BetterZslf"
        header = bytearray(packaged_fixture()[:32])
        struct.pack_into("<I", header, 20, diag.MAX_LAYOUT_BYTES + 1)
        path.write_bytes(header)
        with self.assertRaises(ValueError):
            diag.inspect_macho(path, self.artifacts, "bad-layout")
        self.assertIn("error", json.loads((self.artifacts / "bad-layout.json").read_text()))

    def test_xcode_build_records_actual_tools_settings_and_packaging_inputs(self):
        derived = self.root / "new-build"
        calls = []
        def run(command, **kwargs):
            calls.append((command, kwargs["timeout"]))
            self.assertNotEqual(kwargs["stdout"], smoke.subprocess.PIPE)
            kwargs["stdout"].write(b"SYNTHETIC TOOL OUTPUT, NOT AN ACTUAL SDK VERSION\n")
            if command[-1] == "build":
                directory = derived / "Build/Intermediates.noindex/BetterZslf.build/Debug-iphonesimulator/BetterZslf.build"
                directory.mkdir(parents=True)
                (directory / "BetterZslf.app-Simulated.xcent").write_bytes(b"fixture")
            return mock.Mock(returncode=0)
        with mock.patch.object(diag.sys, "platform", "darwin"):
            diag.build_xcode_simulator(derived, run=run)
        command = calls[-1][0]
        metadata = calls[-2][0]
        result_path = str(derived.resolve() / "build.xcresult")
        self.assertEqual(metadata[-1], "-showBuildSettings")
        self.assertNotIn("-resultBundlePath", metadata)
        self.assertNotIn(result_path, metadata)
        self.assertEqual(command[-1], "build")
        self.assertEqual(command.count("-resultBundlePath"), 1)
        self.assertEqual(command[command.index("-resultBundlePath") + 1], result_path)
        self.assertEqual(sum("-resultBundlePath" in args for args, _ in calls), 1)
        self.assertIn("CODE_SIGNING_ALLOWED=YES", command)
        self.assertIn("CODE_SIGN_IDENTITY=-", command)
        self.assertIn("OTHER_CODE_SIGN_FLAGS=", command)
        self.assertFalse(any("DEVELOPMENT_TEAM" in item for item in command))
        self.assertIn("iphonesimulator", command)
        self.assertEqual([value for _, value in calls], [30, 30, 15, 60, 1200])
        evidence = derived / "Logs/xcode-packaging"
        self.assertEqual(len(json.loads((evidence / "packaging-inputs.json").read_text())), 1)
        self.assertEqual(json.loads((evidence / "build.result.json").read_text())["outcome"], "success")

    def test_xcode_build_timeout_remains_timeout_with_streamed_partial_output(self):
        derived = self.root / "failed-build"
        def run(command, **kwargs):
            kwargs["stdout"].write(b"partial fixture")
            if command[-1] == "build":
                raise smoke.subprocess.TimeoutExpired(command, 1200)
            return mock.Mock(returncode=0)
        with mock.patch.object(diag.sys, "platform", "darwin"), self.assertRaises(smoke.subprocess.TimeoutExpired):
            diag.build_xcode_simulator(derived, run=run)
        evidence = derived / "Logs/xcode-packaging"
        result = json.loads((evidence / "build.result.json").read_text())
        self.assertTrue(result["timed_out"])
        self.assertTrue(result["partial"])
        self.assertEqual(result["stdout_bytes"], len(b"partial fixture"))

    def test_build_timeout_primary_survives_retention_size_failure(self):
        derived = self.root / "failed-retention"
        primary = smoke.subprocess.TimeoutExpired("synthetic-build", 1200)
        def run(command, **kwargs):
            if command[-1] == "build":
                directory = derived / "Build/Intermediates.noindex/BetterZslf.build/Debug-iphonesimulator/BetterZslf.build"
                directory.mkdir(parents=True)
                with (directory / "oversized.xcent").open("wb") as stream:
                    stream.truncate(diag.MAX_LAYOUT_BYTES + 1)
                raise primary
            return mock.Mock(returncode=0)
        with mock.patch.object(diag.sys, "platform", "darwin"), self.assertRaises(smoke.subprocess.TimeoutExpired) as raised:
            diag.build_xcode_simulator(derived, run=run)
        self.assertIs(raised.exception, primary)
        evidence = derived / "Logs/xcode-packaging"
        self.assertEqual(json.loads((evidence / "build.result.json").read_text())["error_type"], "TimeoutExpired")
        self.assertEqual(json.loads((evidence / "packaging-retention-error.json").read_text())["type"], "ValueError")

    def test_parent_xcode_method_propagates_and_keeps_manual_ui_review(self):
        sys.path.insert(0, str(ROOT / "scripts/tests"))
        from test_ios_simulator_smoke import FakeSimctl
        runner = FakeSimctl()
        with mock.patch.object(diag, "run_diagnostics", return_value={"pid": 4321}) as probe:
            value = smoke.smoke(self.artifacts, self.app, runner, sleep=lambda _: None,
                                keychain_diagnostics=True, diagnostic_mode="signed-positive", signing_method="xcode-simulator")
        self.assertEqual(probe.call_args.kwargs["signing_method"], "xcode-simulator")
        self.assertEqual(value["signing_method"], "xcode-simulator")
        self.assertFalse(value["login_ui_verified"])
        self.assertIn("multi-condition", (self.artifacts / "README.md").read_text())

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
        launch_primary = RuntimeError("Synthetic launch denied before PID")
        self.runner.launch_errors["signed-seed-launch"] = launch_primary
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
            with self.assertRaises(RuntimeError) as raised:
                self.run_probe("signed-positive")
        self.assertIs(raised.exception, launch_primary)
        self.assertTrue(smoke.is_smoke_deadline(launch_primary))
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
                expected_primary = primary
                if target == "unsigned-launch-system-log":
                    expected_primary = RuntimeError("Synthetic launch denied before PID")
                    self.runner.launch_errors["unsigned-launch"] = expected_primary
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
                    with self.assertRaises(type(expected_primary)) as raised:
                        self.run_probe()
                self.assertIs(raised.exception, expected_primary)
                self.assertTrue(smoke.is_smoke_deadline(expected_primary))
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
