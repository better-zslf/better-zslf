"""Offline fixtures/mock command flow only; never run xcrun or a simulator."""
import copy
import json
from pathlib import Path
import plistlib
import struct
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import ios_simulator_smoke as smoke

FIXTURE = Path(__file__).parent / "fixtures/ios-simulator/inventory.json"
OWN = "DDDDDDDD-DDDD-4DDD-8DDD-DDDDDDDDDDDD"
BUNDLE = "io.github.betterzslf"


def inventory():
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


class FakeSimctl:
    """The injected test double cannot be selected by the production CLI."""
    def __init__(self):
        self.inventory = inventory()
        self.calls = []
        self.fail = set()
        self.timeout_labels = set()
        self.dead_sample = None
        self.bad_create_output = False
        self.delay_create_polls = 0
        self.pending_creation = None

    def call(self, args, label, timeout=60):
        args = list(map(str, args))
        self.calls.append((args, label, timeout))
        if args[0] == "create":
            entry = dict(udid=OWN, name=args[1], deviceTypeIdentifier=args[2], isAvailable=True, state="Shutdown")
            if self.delay_create_polls:
                self.pending_creation = (args[3], entry)
            else:
                self.inventory["devices"].setdefault(args[3], []).append(entry)
            if label in self.fail:
                raise TimeoutError("Synthetic interrupted create after device registration")
            return "lost stdout" if self.bad_create_output else OWN + "\n"
        if label in self.timeout_labels:
            raise smoke.subprocess.TimeoutExpired("synthetic owned command", timeout)
        if label in self.fail:
            raise RuntimeError("Synthetic command failure: " + label)
        if args[0] == "list":
            if self.pending_creation:
                self.delay_create_polls -= 1
                if self.delay_create_polls <= 0:
                    runtime, entry = self.pending_creation
                    self.inventory["devices"].setdefault(runtime, []).append(entry)
                    self.pending_creation = None
            return json.dumps(self.inventory)
        if args[0] == "launch":
            return f"{args[2]}: 4321\n"
        if args[:3] == ["spawn", OWN, "launchctl"]:
            pid = "-" if label == self.dead_sample else "4321"
            return f"PID\tStatus\tLabel\n{pid}\t0\tUIKitApplication:{BUNDLE}[fixture][rb-legacy]\n"
        if args[0] == "io":
            # A synthetic PNG header, not an image from a native UI.
            Path(args[3]).write_bytes(b"\x89PNG\r\n\x1a\n" + struct.pack(">I", 13) + b"IHDR" + struct.pack(">II", 390, 844))
        if args[0] == "delete":
            for runtime in self.inventory["devices"]:
                self.inventory["devices"][runtime] = [entry for entry in self.inventory["devices"][runtime]
                                                       if entry["udid"] != args[1]]
        return ""


class SimulatorSelectionTests(unittest.TestCase):
    def test_highest_available_ios_and_real_compatible_phone_are_selected(self):
        data = inventory()
        runtime, device_type = smoke.choose_destination(data)
        self.assertEqual(runtime, "com.apple.CoreSimulator.SimRuntime.iOS-26-4")
        self.assertEqual(device_type, "com.apple.CoreSimulator.SimDeviceType.iPhone-17")
        self.assertIn(device_type, {entry["identifier"] for entry in data["devicetypes"]})

    def test_unavailable_ios_and_non_ios_are_never_selected(self):
        data = inventory()
        for entry in data["runtimes"]:
            if "iOS" in entry["identifier"]:
                entry["isAvailable"] = False
        with self.assertRaisesRegex(ValueError, "No installed available"):
            smoke.choose_destination(data)

    def test_explicit_supported_types_prevent_guessing_new_device(self):
        data = inventory()
        data["runtimes"][2]["supportedDeviceTypes"] = ["com.apple.CoreSimulator.SimDeviceType.Not-Installed"]
        self.assertEqual(smoke.choose_destination(data)[0], "com.apple.CoreSimulator.SimRuntime.iOS-18-6")

    def test_runtime_version_bounds_are_used_when_pair_inventory_is_empty(self):
        data = inventory()
        data["devices"] = {}
        data["runtimes"] = [dict(identifier="com.apple.CoreSimulator.SimRuntime.iOS-18-6",
                                 version="18.6", isAvailable=True)]
        data["devicetypes"] = [data["devicetypes"][0], data["devicetypes"][2]]
        self.assertTrue(smoke.choose_destination(data)[1].endswith("iPhone-15"))
        for entry in data["devicetypes"]:
            entry.pop("maxRuntimeVersion")
        with self.assertRaises(ValueError):
            smoke.choose_destination(data)

    def test_no_available_pair_never_synthesizes_a_device_or_downloads_runtime(self):
        data = inventory()
        data["devicetypes"] = []
        with self.assertRaisesRegex(ValueError, "no runtime will be downloaded"):
            smoke.choose_destination(data)

    def test_uuid_and_version_parsers_reject_global_aliases_and_bad_values(self):
        for value in ("booted", "all", "unavailable", "", "not-a-uuid"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                smoke.canonical_udid(value)
        self.assertEqual(smoke.canonical_udid(OWN.lower()), OWN)
        self.assertEqual(smoke.version_code(smoke.version("26.4")), 1704960)

    def test_absence_parser_requires_complete_header_and_valid_nonempty_table(self):
        self.assertEqual(smoke.parse_launchctl_table("PID\tStatus\tLabel\n-\t0\tcom.apple.fixture\n"),
                         [["-", "0", "com.apple.fixture"]])
        for value in ("", "PID Status Label\n", "PID Status Label\n- 0 com.apple.fixture",
                      "PID Status Label\n0 0 com.apple.fixture\n", "PID Status Label\n- bad com.apple.fixture\n",
                      "PID Status Label\n1234 0\n", "Wrong Status Label\n- 0 com.apple.fixture\n"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                smoke.parse_launchctl_table(value)

    def test_pid_parser_and_liveness_require_same_bundle_and_pid(self):
        self.assertEqual(smoke.parse_launch_pid(f"{BUNDLE}: 4321\n", BUNDLE), 4321)
        for value in (f"{BUNDLE}: 0", "other.bundle: 4321", f"{BUNDLE}: 1\n{BUNDLE}: 2"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                smoke.parse_launch_pid(value, BUNDLE)
        self.assertTrue(smoke.process_is_alive(f"4321 0 UIKitApplication:{BUNDLE}[a]", BUNDLE, 4321))
        self.assertFalse(smoke.process_is_alive(f"4322 0 UIKitApplication:{BUNDLE}[a]", BUNDLE, 4321))
        self.assertFalse(smoke.process_is_alive("4321 0 UIKitApplication:other.bundle[a]", BUNDLE, 4321))
        self.assertFalse(smoke.process_is_alive(f"- 1 UIKitApplication:{BUNDLE}[a]", BUNDLE, 4321))


class SimulatorCommandFlowTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.artifacts = self.root / "evidence"
        self.artifacts.mkdir()
        self.app = self.root / "built app" / "BetterZslf.app"
        self.app.mkdir(parents=True)
        self.plist = dict(CFBundleIdentifier=BUNDLE, CFBundleExecutable="BetterZslf",
                          CFBundleSupportedPlatforms=["iPhoneSimulator"])
        self.write_plist()
        (self.app / "BetterZslf").write_bytes(b"Synthetic fixture; not a native executable")
        self.runner = FakeSimctl()
        self.prior = smoke.all_udids(self.runner.inventory)

    def write_plist(self):
        with (self.app / "Info.plist").open("wb") as stream:
            plistlib.dump(self.plist, stream)

    def run_smoke(self):
        return smoke.smoke(self.artifacts, self.app, self.runner, sleep=lambda _: None)

    def state(self):
        return json.loads((self.artifacts / "owner.json").read_text(encoding="utf-8"))

    def assert_only_owned_mutations(self):
        mutating = {"boot", "bootstatus", "install", "launch", "spawn", "io", "terminate", "shutdown", "delete"}
        for args, _, timeout in self.runner.calls:
            self.assertGreater(timeout, 0)
            if args[0] in mutating:
                self.assertEqual(args[1], OWN)
            self.assertNotIn(args[0], {"erase", "privacy", "download", "pair"})
            self.assertNotIn("all", args)
            self.assertNotIn("booted", args)

    def test_initial_inventory_cold_timeout_retries_once_before_any_creation(self):
        self.runner.timeout_labels.add("inventory-before")
        self.run_smoke()
        first, second = self.runner.calls[:2]
        self.assertEqual(first, (["list", "--json"], "inventory-before", 30))
        self.assertEqual(second, (["list", "--json"], "inventory-before-retry", 30))
        self.assertEqual(sum(args[0] == "create" for args, _, _ in self.runner.calls), 1)
        self.assert_only_owned_mutations()

    def test_initial_inventory_exhausted_timeouts_never_create_or_mutate_devices(self):
        self.runner.timeout_labels.update(("inventory-before", "inventory-before-retry"))
        with self.assertRaises(smoke.subprocess.TimeoutExpired):
            self.run_smoke()
        self.assertEqual(len(self.runner.calls), 2)
        self.assertTrue(all(args == ["list", "--json"] for args, _, _ in self.runner.calls))
        self.assertEqual(sum(timeout for _, _, timeout in self.runner.calls), 60)
        self.assertFalse((self.artifacts / "owner.json").exists())
        self.assertEqual(smoke.all_udids(self.runner.inventory), self.prior)

    def test_initial_inventory_nonzero_or_bad_json_is_not_retried(self):
        self.runner.fail.add("inventory-before")
        with self.assertRaises(RuntimeError):
            self.run_smoke()
        self.assertEqual(len(self.runner.calls), 1)
        self.assertFalse((self.artifacts / "owner.json").exists())
        with mock.patch.object(self.runner, "call", return_value="not JSON") as call:
            with self.assertRaises(json.JSONDecodeError):
                self.run_smoke()
            call.assert_called_once_with(["list", "--json"], "inventory-before", 30)
        self.assertFalse((self.artifacts / "owner.json").exists())

    def test_success_records_pid_screenshot_and_only_cleans_its_new_device(self):
        result = self.run_smoke()
        self.assertEqual(result["pid"], 4321)
        self.assertEqual(result["stability_seconds"], 6)
        self.assertEqual(smoke.png_dimensions(self.artifacts / "startup.png"), (390, 844))
        self.assertIn("not full functionality", result["coverage"])
        smoke.cleanup(self.artifacts, self.runner)
        self.assertEqual(smoke.all_udids(self.runner.inventory), self.prior)
        existing_booted = self.runner.inventory["devices"]["com.apple.CoreSimulator.SimRuntime.iOS-26-4"][1]
        self.assertEqual(existing_booted["state"], "Booted")
        self.assert_only_owned_mutations()

    def test_interrupted_create_is_recovered_by_exact_unique_name(self):
        self.runner.fail.add("create")
        with self.assertRaises(TimeoutError):
            self.run_smoke()
        self.assertIsNone(self.state()["udid"])
        smoke.cleanup(self.artifacts, self.runner, fast=True)
        self.assertEqual(smoke.all_udids(self.runner.inventory), self.prior)
        self.assert_only_owned_mutations()

    def test_lost_create_output_also_recovers_without_touching_existing_udid(self):
        self.runner.bad_create_output = True
        with self.assertRaises(ValueError):
            self.run_smoke()
        smoke.cleanup(self.artifacts, self.runner)
        self.assertEqual(smoke.all_udids(self.runner.inventory), self.prior)
        self.assert_only_owned_mutations()

    def test_cleanup_inventory_timeout_retries_then_deletes_only_owned_device(self):
        self.run_smoke()
        self.runner.calls.clear()
        self.runner.timeout_labels.add("cleanup-inventory")
        smoke.cleanup(self.artifacts, self.runner, sleep=lambda _: None)
        value = json.loads((self.artifacts / "cleanup.json").read_text())
        self.assertTrue(value["deleted"])
        self.assertEqual(value["recovery_attempts"], 2)
        self.assertEqual(value["inventory_errors"], [{"attempt": 1, "type": "TimeoutExpired"}])
        self.assertEqual(smoke.all_udids(self.runner.inventory), self.prior)
        self.assert_only_owned_mutations()

    def test_cleanup_exhausted_timeouts_persist_uncertainty_without_cached_uuid_mutation(self):
        self.run_smoke()
        for fast, budget in ((False, 24.0), (True, 2.0)):
            with self.subTest(fast=fast):
                self.runner.calls.clear()
                now = [0.0]
                def call(args, label, timeout=60):
                    self.runner.calls.append((list(args), label, timeout))
                    now[0] += timeout
                    raise smoke.subprocess.TimeoutExpired("synthetic inventory", timeout)
                with mock.patch.object(self.runner, "call", side_effect=call):
                    with self.assertRaisesRegex(RuntimeError, "inventory unavailable"):
                        smoke.cleanup(self.artifacts, self.runner, fast=fast, sleep=lambda _: None,
                                      clock=lambda: now[0])
                value = json.loads((self.artifacts / "cleanup.json").read_text())
                self.assertTrue(value["uncertain"])
                self.assertFalse(value["deleted"])
                self.assertEqual(value["actions"], [])
                self.assertTrue(all(args == ["list", "devices", "--json"] for args, _, _ in self.runner.calls))
                self.assertLessEqual(sum(timeout for _, _, timeout in self.runner.calls), budget)

    def test_cleanup_still_deletes_if_terminate_or_shutdown_fails(self):
        self.run_smoke()
        self.runner.fail.update({"cleanup-terminate", "cleanup-shutdown"})
        smoke.cleanup(self.artifacts, self.runner)
        self.assertEqual(smoke.all_udids(self.runner.inventory), self.prior)
        self.assertTrue(json.loads((self.artifacts / "cleanup.json").read_text())["deleted"])

    def test_delete_failure_is_not_silently_reported_as_cleanup_success(self):
        self.run_smoke()
        self.runner.fail.add("cleanup-delete")
        with self.assertRaisesRegex(RuntimeError, "deletion failed"):
            smoke.cleanup(self.artifacts, self.runner)
        self.assertFalse(json.loads((self.artifacts / "cleanup.json").read_text())["deleted"])

    def test_immediate_crash_fails_without_relaunching_and_can_clean_up(self):
        self.runner.dead_sample = "process-1"
        with self.assertRaisesRegex(RuntimeError, "exited/restarted"):
            self.run_smoke()
        self.assertFalse(self.state()["success"])
        self.assertEqual(sum(args[0] == "launch" for args, _, _ in self.runner.calls), 1)
        smoke.cleanup(self.artifacts, self.runner)
        self.assertEqual(smoke.all_udids(self.runner.inventory), self.prior)

    def test_cleanup_refuses_a_preexisting_udid_even_with_a_valid_name_record(self):
        self.run_smoke()
        state = self.state()
        state["udid"] = next(iter(self.prior))
        smoke.save_json(self.artifacts / "owner.json", state)
        self.runner.calls.clear()
        with self.assertRaisesRegex(ValueError, "pre-existing"):
            smoke.cleanup(self.artifacts, self.runner)
        self.assertEqual([args[0] for args, _, _ in self.runner.calls], ["list"])

    def test_ambiguous_same_name_devices_fail_closed(self):
        self.run_smoke()
        state = self.state()
        state["udid"] = None
        smoke.save_json(self.artifacts / "owner.json", state)
        entry = copy.deepcopy(self.runner.inventory["devices"][state["runtime"]][-1])
        entry["udid"] = "EEEEEEEE-EEEE-4EEE-8EEE-EEEEEEEEEEEE"
        self.runner.inventory["devices"][state["runtime"]].append(entry)
        self.runner.calls.clear()
        with self.assertRaisesRegex(ValueError, "Ambiguous"):
            smoke.cleanup(self.artifacts, self.runner)
        self.assertEqual([args[0] for args, _, _ in self.runner.calls], ["list"])

    def test_non_simulator_or_injected_bundle_executable_is_rejected_before_create(self):
        for key, value in (("CFBundleSupportedPlatforms", ["iPhoneOS"]),
                           ("CFBundleIdentifier", "bad; shell"), ("CFBundleExecutable", "../outside")):
            previous = self.plist[key]
            self.plist[key] = value
            self.write_plist()
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.run_smoke()
            self.assertEqual(self.runner.calls, [])
            self.plist[key] = previous
            self.write_plist()

    def test_fast_signal_cleanup_skips_expensive_diagnostics_but_deletes(self):
        self.run_smoke()
        self.runner.calls.clear()
        smoke.cleanup(self.artifacts, self.runner, fast=True)
        self.assertEqual([args[0] for args, _, _ in self.runner.calls], ["list", "shutdown", "delete"])
        self.assertLessEqual(sum(timeout for _, _, timeout in self.runner.calls), 3.5)
        self.assertEqual(smoke.all_udids(self.runner.inventory), self.prior)

    def test_cleanup_without_owner_is_a_no_op(self):
        smoke.cleanup(self.artifacts, self.runner)
        self.assertEqual(self.runner.calls, [])

    def test_interrupted_create_waits_for_delayed_server_registration(self):
        self.runner.fail.add("create")
        self.runner.delay_create_polls = 3
        with self.assertRaises(TimeoutError):
            self.run_smoke()
        smoke.cleanup(self.artifacts, self.runner, sleep=lambda _: None)
        report = json.loads((self.artifacts / "cleanup.json").read_text())
        self.assertEqual(report["recovery_attempts"], 3)
        self.assertTrue(report["deleted"])
        self.assertEqual(smoke.all_udids(self.runner.inventory), self.prior)
        self.assert_only_owned_mutations()

    def test_unknown_create_outcome_is_uncertain_not_false_deletion_success(self):
        self.runner.fail.add("create")
        self.runner.delay_create_polls = 100
        with self.assertRaises(TimeoutError):
            self.run_smoke()
        self.runner.calls.clear()
        with self.assertRaisesRegex(RuntimeError, "remains uncertain"):
            smoke.cleanup(self.artifacts, self.runner, sleep=lambda _: None, clock=lambda: 0.0)
        report = json.loads((self.artifacts / "cleanup.json").read_text())
        self.assertEqual(report["recovery_attempts"], 6)
        self.assertTrue(report["uncertain"])
        self.assertFalse(report["deleted"])
        self.assertTrue(all(args[0] == "list" for args, _, _ in self.runner.calls))

    def test_command_timeout_does_not_wait_forever_for_inherited_pipes(self):
        process = mock.Mock()
        process.pid = 12345
        process.communicate.side_effect = [
            smoke.subprocess.TimeoutExpired("xcrun", 1, output=b"partial"),
            smoke.subprocess.TimeoutExpired("xcrun", 0.25, output=b"partial"),
        ]
        process.wait.side_effect = smoke.subprocess.TimeoutExpired("xcrun", 0.25)
        kill_signal = getattr(smoke.signal, "SIGKILL", 9)
        with mock.patch.object(smoke.subprocess, "Popen", return_value=process) as popen, \
                mock.patch.object(smoke.os, "killpg", create=True) as killpg, \
                mock.patch.object(smoke.signal, "SIGKILL", kill_signal, create=True):
            with self.assertRaises(smoke.subprocess.TimeoutExpired):
                smoke.Simctl(self.artifacts).call(["list", "--json"], "bounded", timeout=1)
        self.assertTrue(popen.call_args.kwargs["start_new_session"])
        killpg.assert_called_once_with(12345, kill_signal)
        self.assertEqual(process.communicate.call_args_list[1].kwargs["timeout"], 0.25)
        self.assertEqual(process.wait.call_args.kwargs["timeout"], 0.25)
        self.assertEqual((self.artifacts / "bounded.stdout.log").read_bytes(), b"partial")


class SmokeWiringTests(unittest.TestCase):
    def test_shell_has_exit_signal_traps_and_ci_runs_after_native_build(self):
        shell = (smoke.ROOT / "scripts/ci-ios-smoke.sh").read_text()
        self.assertIn("trap cleanup EXIT", shell)
        self.assertIn("trap 'interrupted=1; exit 130' INT", shell)
        self.assertIn("trap 'interrupted=1; exit 143' TERM", shell)
        workflow = (smoke.ROOT / ".github/workflows/ci.yml").read_text()
        self.assertLess(workflow.index("run: bash scripts/ci-ios.sh"), workflow.index("run: bash scripts/ci-ios-smoke.sh"))
        self.assertIn("timeout-minutes: 12\n        run: bash scripts/ci-ios-smoke.sh", workflow)
        self.assertIn(".local/ios-ci/Logs/", workflow)
        helper = (smoke.ROOT / "scripts/ios_simulator_smoke.py").read_text()
        self.assertIn("signal.alarm(600)", helper)  # Reserve 2 minutes for cleanup inside the step.


if __name__ == "__main__":
    unittest.main()
