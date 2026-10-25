"""Offline fixtures/mock command flow only; never run xcrun or a simulator."""
from contextlib import ExitStack
import copy
import io
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
    def test_exact_lower_pin_wins_without_mutating_inventory_or_latest_default(self):
        data = inventory()
        pin = "com.apple.CoreSimulator.SimRuntime.iOS-26-4"
        higher = "com.apple.CoreSimulator.SimRuntime.iOS-26-5"
        data["runtimes"][2]["version"] = "26.4.1"  # Installed version differs from its canonical identifier.
        data["runtimes"].append(dict(identifier=higher, version="26.5", isAvailable=True,
                                     supportedDeviceTypes=["com.apple.CoreSimulator.SimDeviceType.iPhone-17"]))
        original = copy.deepcopy(data)
        self.assertEqual(smoke.choose_destination(data)[0], higher)
        self.assertEqual(smoke.choose_destination(data, requested_runtime=pin),
                         (pin, "com.apple.CoreSimulator.SimDeviceType.iPhone-17"))
        self.assertEqual(smoke.choose_destination(data, "com.apple.CoreSimulator.SimRuntime.iOS-18-6")[1],
                         "com.apple.CoreSimulator.SimDeviceType.iPhone-15")
        self.assertEqual(data, original)

    def test_pin_missing_unavailable_incompatible_approximate_or_invalid_never_falls_back(self):
        pin = "com.apple.CoreSimulator.SimRuntime.iOS-26-4"
        for requested in ("", "all", "iOS-26-4", pin + ".1", pin + "-1", pin + "\n", pin.lower(),
                          "com.apple.CoreSimulator.SimRuntime.iOS-99-0", True, 264, [pin],
                          "com.apple.CoreSimulator.SimRuntime.iOS-28-0"):
            with self.subTest(requested=requested), self.assertRaises(ValueError):
                smoke.choose_destination(inventory(), requested)
        for state in ("unavailable", "incompatible", "duplicate"):
            with self.subTest(state=state):
                data = inventory()
                if state == "unavailable":
                    data["runtimes"][2]["isAvailable"] = False
                elif state == "incompatible":
                    data["runtimes"][2]["supportedDeviceTypes"] = []
                else:
                    data["runtimes"].append(copy.deepcopy(data["runtimes"][2]))
                with self.assertRaisesRegex(ValueError, "Requested runtime"):
                    smoke.choose_destination(data, pin)

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

    def test_pin_selects_exact_runtime_and_rejections_precede_create_or_download(self):
        pin = "com.apple.CoreSimulator.SimRuntime.iOS-26-4"
        self.runner.inventory["runtimes"].append(dict(identifier="com.apple.CoreSimulator.SimRuntime.iOS-26-5",
                                                     version="26.5", isAvailable=True,
                                                     supportedDeviceTypes=["com.apple.CoreSimulator.SimDeviceType.iPhone-17"]))
        smoke.smoke(self.artifacts, self.app, self.runner, sleep=lambda _: None, requested_runtime=pin)
        self.assertEqual(self.state()["runtime"], pin)
        creates = [args for args, _, _ in self.runner.calls if args[0] == "create"]
        self.assertEqual(len(creates), 1)
        self.assertEqual(creates[0][3], pin)
        for requested in ("iOS-26-4", pin + "-1", "com.apple.CoreSimulator.SimRuntime.iOS-28-0", pin):
            with self.subTest(requested=requested):
                self.setUp()
                if requested == pin:
                    self.runner.inventory["runtimes"][2]["supportedDeviceTypes"] = []
                with self.assertRaises(ValueError):
                    smoke.smoke(self.artifacts, self.app, self.runner, requested_runtime=requested)
                self.assertEqual([args for args, _, _ in self.runner.calls], [["list", "--json"]])
                self.assertFalse((self.artifacts / "owner.json").exists())
                self.assertEqual(smoke.all_udids(self.runner.inventory), self.prior)

    def test_runtime_cli_is_explicit_and_ignores_global_runtime_environment(self):
        pin = "com.apple.CoreSimulator.SimRuntime.iOS-26-4"
        for requested in (None, pin):
            argv = ["ios_simulator_smoke.py", "smoke", "--artifacts", str(self.artifacts)]
            if requested is not None:
                argv += ["--runtime", requested]
            with self.subTest(requested=requested), \
                    mock.patch.dict(smoke.os.environ, {"BETTER_ZSLF_CI_RUNTIME": "invalid global pin"}), \
                    mock.patch.object(smoke.sys, "platform", "darwin"), mock.patch.object(smoke.sys, "argv", argv), \
                    mock.patch.object(smoke.signal, "SIGALRM", 14, create=True), \
                    mock.patch.object(smoke.signal, "signal"), mock.patch.object(smoke.signal, "alarm", create=True), \
                    mock.patch.object(smoke, "select_app", return_value=(self.app, BUNDLE, "BetterZslf")), \
                    mock.patch.object(smoke, "smoke", return_value={}) as body, mock.patch("builtins.print"):
                smoke.main()
            self.assertEqual(body.call_args.kwargs["requested_runtime"], requested)

    def test_legacy_parsed_args_without_runtime_remain_compatible(self):
        args = smoke.argparse.Namespace(mode="smoke", artifacts=self.artifacts, app=self.app, fast=False,
                                         keychain_diagnostics=False, diagnostic_mode="ab", signing_method="manual-ad-hoc")
        parser = mock.Mock()
        parser.parse_args.return_value = args
        with mock.patch.object(smoke.argparse, "ArgumentParser", return_value=parser), \
                mock.patch.object(smoke.sys, "platform", "darwin"), \
                mock.patch.object(smoke.signal, "SIGALRM", 14, create=True), \
                mock.patch.object(smoke.signal, "signal"), mock.patch.object(smoke.signal, "alarm", create=True), \
                mock.patch.object(smoke, "select_app", return_value=(self.app, BUNDLE, "BetterZslf")), \
                mock.patch.object(smoke, "smoke", return_value={}) as body, mock.patch("builtins.print"):
            smoke.main()
        self.assertIsNone(body.call_args.kwargs["requested_runtime"])

    def test_existing_600s_deadline_is_propagated_without_rearming_or_extending(self):
        for invalid in (True, float("nan"), float("inf")):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                smoke.smoke(self.artifacts, self.app, self.runner, body_deadline=invalid)
        self.assertEqual(self.runner.calls, [])
        result = smoke.smoke(self.artifacts, self.app, self.runner, sleep=lambda _: None, body_deadline=700.0)
        self.assertEqual(self.runner.body_deadline_monotonic, 700.0)
        self.assertEqual(result["result"], "startup-smoke-passed")
        with mock.patch.object(smoke.sys, "platform", "darwin"), \
                mock.patch.object(smoke.sys, "argv", ["ios_simulator_smoke.py", "smoke", "--artifacts", str(self.artifacts)]), \
                mock.patch.object(smoke.signal, "SIGALRM", 14, create=True), \
                mock.patch.object(smoke.signal, "signal"), \
                mock.patch.object(smoke.signal, "alarm", create=True) as alarm, \
                mock.patch.object(smoke.time, "monotonic", return_value=100.0), \
                mock.patch.object(smoke, "select_app", return_value=(self.app, BUNDLE, "BetterZslf")), \
                mock.patch.object(smoke, "smoke", return_value={}) as body, mock.patch("builtins.print"):
            smoke.main()
        self.assertEqual(body.call_args.kwargs["body_deadline"], 700.0)
        self.assertEqual(alarm.call_args_list, [mock.call(600), mock.call(0)])

    def test_exported_png_checks_returned_filename_not_any_leftover(self):
        path = self.artifacts / "shot-attempt-2.png"
        self.runner.call(["io", OWN, "screenshot", str(path)], "fixture-image")
        self.assertTrue(smoke.exported_png_is_valid(self.artifacts, path.name))
        self.assertFalse(smoke.exported_png_is_valid(self.artifacts, None))
        self.assertFalse(smoke.exported_png_is_valid(self.artifacts, "shot.png"))
        self.assertFalse(smoke.exported_png_is_valid(self.artifacts, "../shot-attempt-2.png"))
        path.write_bytes(b"not a PNG")
        self.assertFalse(smoke.exported_png_is_valid(self.artifacts, path.name))

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

    def test_command_result_success_and_nonzero_are_structured_without_environment(self):
        for label, code in (("normal", 0), ("nonzero", 7)):
            with self.subTest(code=code):
                process = mock.Mock(returncode=code)
                process.communicate.return_value = (b"table", b"notice")
                with mock.patch.object(smoke.subprocess, "Popen", return_value=process):
                    if code:
                        with self.assertRaisesRegex(RuntimeError, "failed"):
                            smoke.Simctl(self.artifacts).call(["list", "--json"], label, 3)
                    else:
                        self.assertEqual(smoke.Simctl(self.artifacts).call(["list", "--json"], label, 3), "table")
                value = json.loads((self.artifacts / (label + ".result.json")).read_text())
                self.assertEqual(value["outcome"], "success" if not code else "nonzero")
                self.assertEqual(value["returncode"], code)
                self.assertEqual(value["ok"], not bool(code))
                self.assertFalse(value["timed_out"])
                self.assertTrue(value["drain_complete"])
                self.assertEqual(value["stdout_bytes"], 5)
                for field in ("started_at_utc", "finished_at_utc", "started_monotonic", "finished_monotonic", "elapsed_seconds"):
                    self.assertIn(field, value)
                self.assertNotIn("env", value)

    def test_timeout_stays_timeout_when_cleanup_returns_zero(self):
        process = mock.Mock(pid=12345, returncode=0)
        process.communicate.side_effect = [smoke.subprocess.TimeoutExpired("xcrun", 1, output=b"partial"),
                                           (b"partial-late", b"late-error")]
        with mock.patch.object(smoke.subprocess, "Popen", return_value=process), \
                mock.patch.object(smoke.os, "killpg", create=True), \
                mock.patch.object(smoke.signal, "SIGKILL", 9, create=True):
            with self.assertRaises(smoke.subprocess.TimeoutExpired):
                smoke.Simctl(self.artifacts).call(["list", "--json"], "late-zero", 1)
        value = json.loads((self.artifacts / "late-zero.result.json").read_text())
        self.assertEqual(value["outcome"], "timeout")
        self.assertTrue(value["timed_out"])
        self.assertFalse(value["ok"])
        self.assertIsNone(value["returncode"])
        self.assertEqual(value["post_cleanup_returncode"], 0)
        self.assertTrue(value["drain_complete"])
        self.assertTrue(value["partial"])  # Complete drain does not complete the timed-out operation.

    def test_primary_timeout_survives_close_wait_and_poll_errors(self):
        process = mock.Mock(pid=12345, returncode=None)
        process.communicate.side_effect = [smoke.subprocess.TimeoutExpired("xcrun", 1, output=b"partial"),
                                           smoke.subprocess.TimeoutExpired("xcrun", 0.25, output=b"partial")]
        process.stdout.close.side_effect = OSError("synthetic close")
        process.wait.side_effect = OSError("synthetic wait")
        process.poll.side_effect = OSError("synthetic poll")
        with mock.patch.object(smoke.subprocess, "Popen", return_value=process), \
                mock.patch.object(smoke.os, "killpg", create=True), \
                mock.patch.object(smoke.signal, "SIGKILL", 9, create=True):
            with self.assertRaises(smoke.subprocess.TimeoutExpired):
                smoke.Simctl(self.artifacts).call(["list", "--json"], "cleanup-errors", 1)
        value = json.loads((self.artifacts / "cleanup-errors.result.json").read_text())
        self.assertEqual(value["error_type"], "TimeoutExpired")
        self.assertEqual(value["outcome"], "timeout")
        self.assertTrue(value["partial"])
        self.assertTrue(any(item["operation"] == "close-stdout" for item in value["cleanup_errors"]))
        self.assertEqual((self.artifacts / "cleanup-errors.stdout.log").read_bytes(), b"partial")

    def test_watchdog_during_timeout_drain_marks_same_primary_exception(self):
        primary = smoke.subprocess.TimeoutExpired("xcrun", 1, output=b"partial")
        process = mock.Mock(pid=12345, returncode=0)
        process.communicate.side_effect = [primary, smoke.SmokeDeadlineExceeded("body expired during drain")]
        with mock.patch.object(smoke.subprocess, "Popen", return_value=process), \
                mock.patch.object(smoke.os, "killpg", create=True), \
                mock.patch.object(smoke.signal, "SIGKILL", 9, create=True):
            with self.assertRaises(smoke.subprocess.TimeoutExpired) as raised:
                smoke.Simctl(self.artifacts).call(["list", "--json"], "drain-watchdog", 1)
        self.assertIs(raised.exception, primary)
        self.assertTrue(smoke.is_smoke_deadline(primary))
        value = json.loads((self.artifacts / "drain-watchdog.result.json").read_text())
        self.assertEqual(value["error_type"], "TimeoutExpired")
        self.assertEqual(value["outcome"], "timeout")
        self.assertTrue(value["body_deadline_expired"])
        self.assertTrue(value["partial"])
        self.assertFalse(value["ok"])
        self.assertEqual(value["post_cleanup_returncode"], 0)

    def test_watchdog_is_timeout_and_is_not_operational_exception(self):
        self.assertFalse(issubclass(smoke.SmokeDeadlineExceeded, Exception))
        process = mock.Mock(pid=12345, returncode=0)
        process.communicate.side_effect = [smoke.SmokeDeadlineExceeded("body expired"), (b"", b"")]
        with mock.patch.object(smoke.subprocess, "Popen", return_value=process), \
                mock.patch.object(smoke.os, "killpg", create=True), \
                mock.patch.object(smoke.signal, "SIGKILL", 9, create=True):
            with self.assertRaises(smoke.SmokeDeadlineExceeded):
                smoke.Simctl(self.artifacts).call(["list", "--json"], "watchdog", 1)
        value = json.loads((self.artifacts / "watchdog.result.json").read_text())
        self.assertTrue(value["timed_out"])
        self.assertEqual(value["outcome"], "timeout")
        self.assertFalse(value["ok"])

    def test_invalid_diagnostic_selection_fails_before_creating_device(self):
        for kwargs in ({"diagnostic_mode": "unknown"}, {"diagnostic_mode": "signed-positive"}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                smoke.smoke(self.artifacts, self.app, self.runner, **kwargs)
            self.assertEqual(self.runner.calls, [])

    def test_invalid_signing_method_or_ab_combination_never_creates_device(self):
        for kwargs in ({"signing_method": "unknown"},
                       {"signing_method": "xcode-simulator", "keychain_diagnostics": True},
                       {"signing_method": "xcode-simulator", "diagnostic_mode": "signed-positive"}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                smoke.smoke(self.artifacts, self.app, self.runner, **kwargs)
            self.assertEqual(self.runner.calls, [])

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


class ObserverCommandTests(unittest.TestCase):
    """All native/host subprocesses are fake; no ps/vm_stat command is executed."""
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.artifacts = Path(temporary.name)
        self.runner = smoke.Simctl(self.artifacts)

    def record(self, label):
        return json.loads((self.artifacts / (label + ".result.json")).read_text())

    def patched(self, process, payloads=None):
        stack = ExitStack()
        stack.enter_context(mock.patch.object(smoke.sys, "platform", "darwin"))
        def popen(command, **kwargs):
            if payloads is not None:
                for name, payload in zip(("stdout", "stderr"), payloads):
                    self.assertNotEqual(kwargs[name], smoke.subprocess.PIPE)
                    kwargs[name].write(payload)
            return process
        self.popen = stack.enter_context(mock.patch.object(smoke.subprocess, "Popen", side_effect=popen))
        self.kill = stack.enter_context(mock.patch.object(smoke.os, "killpg", create=True))
        stack.enter_context(mock.patch.object(smoke.signal, "SIGKILL", 9, create=True))
        return stack

    def test_poll_precedes_kill_and_records_exact_exit_running_or_unknown(self):
        for index, code in enumerate((0, None, False, OSError("poll failed"))):
            with self.subTest(code=code):
                primary = smoke.subprocess.TimeoutExpired("fixture", 1)
                process = mock.Mock(pid=12345, returncode=None)
                process.communicate.side_effect = [primary, (b"partial", b"")]
                events = []
                def poll():
                    events.append("poll")
                    if isinstance(code, BaseException):
                        raise code
                    return code
                process.poll.side_effect = poll
                label = "poll-" + str(index)
                with self.patched(process):
                    self.kill.side_effect = lambda *args: events.append("kill")
                    with self.assertRaises(smoke.subprocess.TimeoutExpired) as raised:
                        self.runner.call(["launch", OWN, BUNDLE], label, 1)
                self.assertIs(raised.exception, primary)
                self.assertEqual(events[:2], ["poll", "kill"])
                record = self.record(label)
                observed = record["pre_cleanup_poll"]
                self.assertEqual(observed["state"], "exited" if type(code) is int else
                                 "running" if code is None else "unknown")
                self.assertEqual(observed["returncode"], code if type(code) is int else None)
                self.assertIsInstance(observed["observed_at_utc"], str)
                self.assertIsInstance(observed["observed_monotonic"], (float, int))
                self.assertFalse(record["ok"])
                self.assertEqual(record["outcome"], "timeout")
                self.assertIsNone(record["returncode"])

    def test_primary_timeout_survives_each_secondary_output_finish_or_save_error(self):
        for operation in ("output", "finish", "save", "all"):
            with self.subTest(operation=operation):
                primary = smoke.subprocess.TimeoutExpired("fixture", 1, output=b"partial")
                secondary = OSError("secondary evidence IO")
                process = mock.Mock(pid=12345, returncode=0)
                process.poll.side_effect = OSError("secondary poll")
                process.communicate.side_effect = [primary, (b"partial", b"")]
                save = smoke.save_json
                def saving(path, value):
                    if type(value) is dict and value.get("outcome") != "running":
                        raise secondary
                    save(path, value)
                with self.patched(process), ExitStack() as patches:
                    if operation in ("output", "all"):
                        patches.enter_context(mock.patch.object(self.runner, "output", side_effect=secondary))
                    if operation in ("finish", "all"):
                        patches.enter_context(mock.patch.object(smoke, "finish_command_result", side_effect=secondary))
                    if operation in ("save", "all"):
                        patches.enter_context(mock.patch.object(smoke, "save_json", side_effect=saving))
                    with self.assertRaises(smoke.subprocess.TimeoutExpired) as raised:
                        self.runner.call(["launch", OWN, BUNDLE], "primary-" + operation, 1)
                self.assertIs(raised.exception, primary)

    def test_primary_interrupt_and_deadline_survive_final_evidence_errors(self):
        for primary in (KeyboardInterrupt("interrupted"), smoke.SmokeDeadlineExceeded("expired")):
            with self.subTest(primary=type(primary).__name__):
                process = mock.Mock(pid=12345, returncode=0)
                process.poll.side_effect = OSError("secondary poll")
                process.communicate.side_effect = [primary, (b"", b"")]
                with self.patched(process), \
                        mock.patch.object(self.runner, "output", side_effect=OSError("secondary output")), \
                        mock.patch.object(smoke, "finish_command_result", side_effect=OSError("secondary finish")):
                    with self.assertRaises(type(primary)) as raised:
                        self.runner.call(["launch", OWN, BUNDLE], type(primary).__name__, 1)
                self.assertIs(raised.exception, primary)
                self.assertFalse(self.record(type(primary).__name__)["ok"])

    def test_success_is_not_returned_when_final_output_finish_or_save_fails(self):
        for operation in ("output", "finish", "save"):
            with self.subTest(operation=operation):
                secondary = OSError("evidence failed")
                process = mock.Mock(returncode=0)
                process.communicate.return_value = (b"full", b"")
                save = smoke.save_json
                def saving(path, value):
                    if type(value) is dict and value.get("outcome") != "running":
                        raise secondary
                    save(path, value)
                target, name = ((self.runner, "output") if operation == "output" else
                                (smoke, "finish_command_result") if operation == "finish" else (smoke, "save_json"))
                with self.patched(process), mock.patch.object(target, name,
                        side_effect=saving if operation == "save" else secondary):
                    with self.assertRaises(OSError) as raised:
                        self.runner.call(["list", "--json"], "failed-" + operation, 1)
                self.assertIs(raised.exception, secondary)
                self.assertFalse(self.record("failed-" + operation)["ok"])

    def test_later_watchdog_marks_first_evidence_error_without_replacing_identity(self):
        for host in (False, True):
            with self.subTest(host=host):
                first = OSError("first evidence write failure")
                process = mock.Mock(pid=12345, returncode=0)
                process.communicate.return_value = (b"complete", b"")
                label = "late-watchdog-host" if host else "late-watchdog-simctl"
                with self.patched(process, (b"complete\n", b"") if host else None), \
                        mock.patch.object(self.runner, "output", side_effect=first), \
                        mock.patch.object(smoke, "finish_command_result", side_effect=smoke.SmokeDeadlineExceeded("late deadline")):
                    with self.assertRaises(OSError) as raised:
                        if host:
                            self.runner.call_host("ps", label, 3)
                        else:
                            self.runner.call(["list", "--json"], label, 3)
                self.assertIs(raised.exception, first)
                self.assertTrue(smoke.is_smoke_deadline(first))
                self.assertTrue(self.record(label)["body_deadline_expired"])
                self.assertFalse(self.record(label)["ok"])

    def test_host_allowlist_timeout_and_label_reject_before_popen(self):
        for kind, label, timeout in (("sample", "host", 1), ("/bin/ps", "host", 1),
                                    (["ps"], "host", 1), ("ps", "../host", 1),
                                    ("ps", "host", 0), ("ps", "host", -1), ("ps", "host", 3.01),
                                    ("ps", "host", True), ("ps", "host", float("nan")),
                                    ("ps", "host", float("inf")), ("ps", "host", "3")):
            with self.subTest(kind=kind, timeout=timeout), mock.patch.object(smoke.subprocess, "Popen") as popen:
                with self.assertRaises(ValueError):
                    self.runner.call_host(kind, label, timeout)
                popen.assert_not_called()

    def test_host_fixed_argv_file_backed_complete_metadata_without_env_or_args(self):
        for kind, command in (("ps", ["/bin/ps", "-Aww", "-o", "pid=,ppid=,comm="]),
                              ("vm_stat", ["/usr/bin/vm_stat"])):
            with self.subTest(kind=kind):
                process = mock.Mock(pid=12345, returncode=0)
                label = "host-" + kind
                with self.patched(process, (b"fixture-output\n", b"fixture-notice\n")):
                    self.assertEqual(self.runner.call_host(kind, label, 3), "fixture-output\n")
                self.popen.assert_called_once()
                self.assertEqual(self.popen.call_args.args[0], command)
                kwargs = self.popen.call_args.kwargs
                self.assertTrue(kwargs["start_new_session"])
                self.assertEqual(kwargs["stdin"], smoke.subprocess.DEVNULL)
                self.assertNotIn("env", kwargs)
                process.communicate.assert_not_called()
                self.kill.assert_not_called()
                record = self.record(label)
                self.assertEqual(record["execution_context"], "host")
                self.assertEqual(record["host_kind"], kind)
                self.assertEqual(record["capture_mode"], "file-backed-prefix")
                self.assertEqual(record["outcome"], "success")
                self.assertTrue(record["ok"])
                self.assertFalse(record["partial"])
                self.assertFalse(record["truncated"])
                self.assertEqual(record["returncode"], 0)
                self.assertEqual(record["stdout_bytes"], len(b"fixture-output\n"))
                self.assertEqual(record["stderr_total_bytes"], len(b"fixture-notice\n"))
                for field in ("started_at_utc", "finished_at_utc", "started_monotonic", "finished_monotonic"):
                    self.assertIn(field, record)
                self.assertEqual(json.loads((self.artifacts / (label + ".command.json")).read_text()), command)

    def test_host_unavailable_os_never_executes_fallback(self):
        with mock.patch.object(smoke.sys, "platform", "win32"), mock.patch.object(smoke.subprocess, "Popen") as popen:
            with self.assertRaises(OSError):
                self.runner.call_host("ps", "no-mac", 1)
        popen.assert_not_called()
        record = self.record("no-mac")
        self.assertEqual(record["error_type"], "OSError")
        self.assertFalse(record["ok"])
        self.assertTrue(record["partial"])

    def test_host_missing_tool_and_nonzero_fail_once_with_evidence(self):
        primary = FileNotFoundError("fixture missing fixed tool")
        with mock.patch.object(smoke.sys, "platform", "darwin"), \
                mock.patch.object(smoke.subprocess, "Popen", side_effect=primary) as popen:
            with self.assertRaises(FileNotFoundError) as raised:
                self.runner.call_host("vm_stat", "missing-tool", 3)
        self.assertIs(raised.exception, primary)
        popen.assert_called_once()
        self.assertFalse(self.record("missing-tool")["ok"])
        process = mock.Mock(pid=12345, returncode=7)
        with self.patched(process, (b"partial-data", b"fixed-tool failed")):
            with self.assertRaises(RuntimeError):
                self.runner.call_host("vm_stat", "nonzero-host", 3)
        self.popen.assert_called_once()
        self.kill.assert_not_called()
        record = self.record("nonzero-host")
        self.assertEqual(record["outcome"], "nonzero")
        self.assertEqual(record["returncode"], 7)
        self.assertFalse(record["ok"])
        self.assertEqual((self.artifacts / "nonzero-host.stderr.log").read_bytes(), b"fixed-tool failed")

    def test_host_prefix_limits_fail_closed_on_either_truncated_stream(self):
        for stdout, stderr in ((b"s" * (256 * 1024 + 1), b""), (b"full\n", b"e" * (16 * 1024 + 1))):
            with self.subTest(stdout=len(stdout), stderr=len(stderr)):
                process = mock.Mock(pid=12345, returncode=0)
                with self.patched(process, (stdout, stderr)):
                    with self.assertRaisesRegex(ValueError, "bounded output"):
                        self.runner.call_host("ps", "truncated-host", 3)
                record = self.record("truncated-host")
                self.assertEqual(record["outcome"], "truncated")
                self.assertFalse(record["ok"])
                self.assertTrue(record["partial"])
                self.assertTrue(record["truncated"])
                self.assertEqual(record["stdout_total_bytes"], len(stdout))
                self.assertEqual(record["stderr_total_bytes"], len(stderr))
                self.assertLessEqual((self.artifacts / "truncated-host.stdout.log").stat().st_size, 256 * 1024)
                self.assertLessEqual((self.artifacts / "truncated-host.stderr.log").stat().st_size, 16 * 1024)

    def test_host_timeout_reserves_cleanup_inside_budget_and_never_uses_poll_zero_as_success(self):
        now, waits = [100.0], []
        primary = smoke.subprocess.TimeoutExpired("fixed host fixture", 2.75)
        process = mock.Mock(pid=12345, returncode=None)
        process.poll.return_value = 0
        def wait(timeout):
            waits.append(timeout)
            now[0] += timeout
            if len(waits) == 1:
                raise primary
            process.returncode = -9
            return -9
        process.wait.side_effect = wait
        with self.patched(process, (b"partial-host", b"")), \
                mock.patch.object(smoke.time, "monotonic", side_effect=lambda: now[0]):
            with self.assertRaises(smoke.subprocess.TimeoutExpired) as raised:
                self.runner.call_host("ps", "timeout-host", 3)
        self.assertIs(raised.exception, primary)
        self.assertEqual(waits, [2.75, 0.25])
        self.assertLessEqual(sum(waits), 3)
        self.kill.assert_called_once_with(12345, 9)
        record = self.record("timeout-host")
        self.assertEqual(record["pre_cleanup_poll"]["returncode"], 0)
        self.assertEqual(record["pre_cleanup_poll"]["state"], "exited")
        self.assertEqual(record["outcome"], "timeout")
        self.assertFalse(record["ok"])
        self.assertTrue(record["partial"])
        self.assertIsNone(record["returncode"])
        self.assertEqual(record["post_cleanup_returncode"], -9)
        self.assertEqual(record["stdout_bytes"], len(b"partial-host"))
        self.assertEqual(record["elapsed_seconds"], 3)

    def test_host_primary_timeout_interrupt_deadline_survive_secondary_evidence_errors(self):
        for primary in (smoke.subprocess.TimeoutExpired("fixture", 1), KeyboardInterrupt("interrupt"),
                        smoke.SmokeDeadlineExceeded("deadline")):
            with self.subTest(primary=type(primary).__name__):
                process = mock.Mock(pid=12345, returncode=0)
                process.wait.side_effect = [primary, 0]
                process.poll.side_effect = OSError("secondary poll")
                with self.patched(process, (b"partial", b"")), \
                        mock.patch.object(self.runner, "output", side_effect=OSError("secondary output")), \
                        mock.patch.object(smoke, "finish_command_result", side_effect=OSError("secondary finish")):
                    with self.assertRaises(type(primary)) as raised:
                        self.runner.call_host("ps", "host-" + type(primary).__name__, 1)
                self.assertIs(raised.exception, primary)
                self.assertFalse(self.record("host-" + type(primary).__name__)["ok"])

    def test_host_capture_reads_only_bounded_prefix_and_invalid_utf8_fails_closed(self):
        streams = []
        class BoundedStream(io.BytesIO):
            def __init__(self):
                super().__init__()
                self.read_sizes = []
            def read(self, size=-1):
                self.read_sizes.append(size)
                if size <= 0:
                    raise AssertionError("unbounded read")
                return super().read(size)
        def temporary_file(**kwargs):
            stream = BoundedStream()
            streams.append(stream)
            return stream
        process = mock.Mock(pid=12345, returncode=0)
        with self.patched(process, (b"\xff", b"")), \
                mock.patch.object(smoke.tempfile, "TemporaryFile", side_effect=temporary_file):
            with self.assertRaises(UnicodeDecodeError):
                self.runner.call_host("ps", "bad-host-utf8", 3)
        self.assertEqual([stream.read_sizes for stream in streams], [[256 * 1024], [16 * 1024]])
        self.assertFalse(self.record("bad-host-utf8")["ok"])


    def test_host_tiny_wait_cleanup_budget_and_bool_exit_status_fail_closed(self):
        now, waits = [0.0], []
        primary = smoke.subprocess.TimeoutExpired("tiny fixture", 0.0375)
        process = mock.Mock(pid=12345, returncode=None)
        process.poll.return_value = None
        def wait(timeout):
            waits.append(timeout)
            now[0] += timeout
            if len(waits) == 1:
                raise primary
            process.returncode = -9
            return -9
        process.wait.side_effect = wait
        with self.patched(process, (b"", b"")), \
                mock.patch.object(smoke.time, "monotonic", side_effect=lambda: now[0]):
            with self.assertRaises(smoke.subprocess.TimeoutExpired) as raised:
                self.runner.call_host("ps", "tiny-host", 0.05)
        self.assertIs(raised.exception, primary)
        self.assertAlmostEqual(sum(waits), 0.05)
        self.assertAlmostEqual(waits[1], 0.0125)
        self.assertEqual(self.record("tiny-host")["pre_cleanup_poll"]["state"], "running")
        process = mock.Mock(pid=12345, returncode=False)
        with self.patched(process, (b"untrusted exit type", b"")):
            with self.assertRaisesRegex(RuntimeError, "exact exit status"):
                self.runner.call_host("ps", "bool-exit", 1)
        self.assertFalse(self.record("bool-exit")["ok"])
        self.assertIsNone(self.record("bool-exit")["returncode"])

    def test_host_primary_survives_read_close_final_save_and_secondary_deadline(self):
        for capture_error in (OSError("capture IO"), smoke.SmokeDeadlineExceeded("capture deadline")):
            with self.subTest(capture_error=type(capture_error).__name__):
                primary = smoke.subprocess.TimeoutExpired("fixture", 1)
                class FaultyStream(io.BytesIO):
                    def read(self, size=-1):
                        raise capture_error
                    def close(self):
                        was_closed = self.closed
                        super().close()
                        if not was_closed:
                            raise OSError("secondary close")
                process = mock.Mock(pid=12345, returncode=0)
                process.wait.side_effect = [primary, 0]
                process.poll.return_value = None
                save = smoke.save_json
                def saving(path, value):
                    if type(value) is dict and value.get("outcome") != "running":
                        raise OSError("secondary final save")
                    save(path, value)
                with self.patched(process, (b"partial", b"")), \
                        mock.patch.object(smoke.tempfile, "TemporaryFile", side_effect=lambda **kwargs: FaultyStream()), \
                        mock.patch.object(smoke, "save_json", side_effect=saving):
                    with self.assertRaises(smoke.subprocess.TimeoutExpired) as raised:
                        self.runner.call_host("ps", "host-io-primary", 1)
                self.assertIs(raised.exception, primary)
                self.assertEqual(smoke.is_smoke_deadline(primary), smoke.is_smoke_deadline(capture_error))
                self.assertFalse(self.record("host-io-primary")["ok"])

    def test_host_success_cannot_escape_evidence_output_finish_or_save_failures(self):
        for operation in ("output", "finish", "save"):
            with self.subTest(operation=operation):
                secondary = OSError("host evidence failed")
                process = mock.Mock(pid=12345, returncode=0)
                save = smoke.save_json
                def saving(path, value):
                    if type(value) is dict and value.get("outcome") != "running":
                        raise secondary
                    save(path, value)
                target, name = ((self.runner, "output") if operation == "output" else
                                (smoke, "finish_command_result") if operation == "finish" else (smoke, "save_json"))
                with self.patched(process, (b"complete", b"")), mock.patch.object(target, name,
                        side_effect=saving if operation == "save" else secondary):
                    with self.assertRaises(OSError) as raised:
                        self.runner.call_host("ps", "host-io-" + operation, 1)
                self.assertIs(raised.exception, secondary)
                self.assertFalse(self.record("host-io-" + operation)["ok"])


class SmokeWiringTests(unittest.TestCase):
    def test_runtime_pin_wiring_is_smoke_only_exact_array_and_sdk_line(self):
        shell = (smoke.ROOT / "scripts/ci-ios-smoke.sh").read_text()
        self.assertIn("runtime_args=()", shell)
        self.assertIn('if [[ -n "${BETTER_ZSLF_CI_RUNTIME:-}" ]]; then', shell)
        self.assertIn('runtime_args=(--runtime "$BETTER_ZSLF_CI_RUNTIME")', shell)
        smoke_lines = [line for line in shell.splitlines() if "python3 scripts/ios_simulator_smoke.py smoke " in line]
        cleanup_lines = [line for line in shell.splitlines() if "python3 scripts/ios_simulator_smoke.py cleanup " in line]
        self.assertEqual(len(smoke_lines), 2)
        self.assertEqual(len(cleanup_lines), 2)
        self.assertTrue(all('${runtime_args[@]+"${runtime_args[@]}"}' in line for line in smoke_lines))
        self.assertTrue(all("runtime_args" not in line and "--runtime" not in line for line in cleanup_lines))
        self.assertNotIn("BETTER_ZSLF_CI_RUNTIME", (smoke.ROOT / "scripts/ios_simulator_smoke.py").read_text())
        workflow = (smoke.ROOT / ".github/workflows/ci.yml").read_text()
        step = workflow.split("      - name: Smoke launch on an owned iOS Simulator\n", 1)[1].split("      - name:", 1)[0]
        self.assertEqual(workflow.count("BETTER_ZSLF_CI_RUNTIME:"), 1)
        self.assertIn("          BETTER_ZSLF_CI_RUNTIME: com.apple.CoreSimulator.SimRuntime.iOS-26-4\n", step)
        self.assertIn("BETTER_ZSLF_CI_DIAGNOSTIC_MODE: signed-positive", step)
        self.assertIn("/Applications/Xcode_26.4.1.app/Contents/Developer", workflow)
        self.assertIn("BETTER_ZSLF_CI_SIGNING_METHOD: xcode-simulator", workflow)
        self.assertIn("timeout-minutes: 12", step)

    def test_xcode_method_is_shared_by_build_and_smoke_without_changing_manual_default(self):
        workflow = (smoke.ROOT / ".github/workflows/ci.yml").read_text()
        ios = workflow[workflow.index("  ios:"):]
        self.assertEqual(ios.count("BETTER_ZSLF_CI_SIGNING_METHOD: xcode-simulator"), 1)
        self.assertLess(ios.index("BETTER_ZSLF_CI_SIGNING_METHOD: xcode-simulator"), ios.index("    steps:"))
        shell = (smoke.ROOT / "scripts/ci-ios.sh").read_text()
        self.assertIn("${BETTER_ZSLF_CI_SIGNING_METHOD:-manual-ad-hoc}", shell)
        self.assertIn("build-xcode --artifacts .local/ios-ci", shell)
        self.assertIn("CODE_SIGNING_ALLOWED=NO", shell)
        self.assertNotIn("DEVELOPMENT_TEAM=", shell)

    def test_shell_has_exit_signal_traps_and_ci_runs_after_native_build(self):
        shell = (smoke.ROOT / "scripts/ci-ios-smoke.sh").read_text()
        self.assertIn("trap cleanup EXIT", shell)
        self.assertIn("trap 'interrupted=1; exit 130' INT", shell)
        self.assertIn("trap 'interrupted=1; exit 143' TERM", shell)
        workflow = (smoke.ROOT / ".github/workflows/ci.yml").read_text()
        self.assertLess(workflow.index("run: bash scripts/ci-ios.sh"), workflow.index("run: bash scripts/ci-ios-smoke.sh"))
        self.assertIn("timeout-minutes: 12\n        run: bash scripts/ci-ios-smoke.sh", workflow)
        self.assertIn(".local/ios-ci/Logs/", workflow)
        self.assertIn("BETTER_ZSLF_CI_DIAGNOSTIC_MODE: signed-positive", workflow)
        helper = (smoke.ROOT / "scripts/ios_simulator_smoke.py").read_text()
        self.assertIn("signal.alarm(600)", helper)  # Reserve 2 minutes for cleanup inside the step.


if __name__ == "__main__":
    unittest.main()
