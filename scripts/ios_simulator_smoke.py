#!/usr/bin/env python3
"""Startup-only iOS Simulator smoke. No credentials, UI input or runtime downloads.

ci-ios-smoke.sh owns the EXIT/signal trap. This helper persists ownership before
create so cleanup can recover a create whose stdout was lost. All mutation and
spawn commands require the exact newly created UUID; never 'booted' or 'all'.
"""
import argparse
import json
import os
from pathlib import Path
import plistlib
import re
import signal
import struct
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
BUNDLE_RE = re.compile(r"[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)+")
RUNTIME_RE = re.compile(r"com\.apple\.CoreSimulator\.SimRuntime\.iOS-[0-9]+(?:-[0-9]+)*")
TYPE_RE = re.compile(r"com\.apple\.CoreSimulator\.SimDeviceType\.[A-Za-z0-9_.-]+")
NAME_PREFIX = "better-zslf-smoke-"


def save_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    temporary.replace(path)


def canonical_udid(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", value):
        raise ValueError("Require an explicit simulator UUID")
    return str(uuid.UUID(value)).upper()


def version(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9]+(?:\.[0-9]+){0,2}", value):
        raise ValueError("Invalid runtime version")
    parts = tuple(int(part) for part in value.split("."))
    return parts + (0,) * (3 - len(parts))


def version_code(parts):
    return (parts[0] << 16) | (parts[1] << 8) | parts[2]


def devices(inventory):
    for runtime, entries in inventory.get("devices", {}).items():
        for entry in entries:
            yield runtime, entry


def all_udids(inventory):
    return {canonical_udid(entry["udid"]) for _, entry in devices(inventory) if entry.get("udid")}


def choose_destination(inventory):
    """Use only installed, available iOS runtime and discovered compatible types."""
    types = [entry for entry in inventory.get("devicetypes", [])
             if TYPE_RE.fullmatch(entry.get("identifier", "")) and
             (entry.get("productFamily") in ("iPhone", "iPad") or
              entry.get("name", "").startswith(("iPhone", "iPad")))]
    runtimes = [entry for entry in inventory.get("runtimes", [])
                if entry.get("isAvailable") is True and RUNTIME_RE.fullmatch(entry.get("identifier", ""))]
    runtimes.sort(key=lambda entry: version(entry["version"]), reverse=True)
    for runtime in runtimes:
        runtime_id = runtime["identifier"]
        code = version_code(version(runtime["version"]))
        proven = {entry.get("deviceTypeIdentifier") for key, entry in devices(inventory)
                  if key == runtime_id and entry.get("isAvailable") is True}
        supported = runtime.get("supportedDeviceTypes")
        supported_ids = {entry.get("identifier") if isinstance(entry, dict) else entry
                         for entry in supported} if isinstance(supported, list) else None
        candidates = []
        for device_type in types:
            identifier = device_type["identifier"]
            if supported_ids is not None:
                compatible = identifier in supported_ids
            elif identifier in proven:
                compatible = True
            else:
                minimum, maximum = device_type.get("minRuntimeVersion"), device_type.get("maxRuntimeVersion")
                compatible = (type(minimum) is int and type(maximum) is int and minimum <= code <= maximum)
            if compatible:
                candidates.append(device_type)
        if candidates:
            candidates.sort(key=lambda item: (0 if item.get("name", "").startswith("iPhone") else 1,
                                              0 if item["identifier"] in proven else 1,
                                              item.get("name", ""), item["identifier"]))
            return runtime_id, candidates[0]["identifier"]
    raise ValueError("No installed available iOS runtime/device-type pair; no runtime will be downloaded")


def app_metadata(app):
    app = Path(app).resolve()
    if app.suffix != ".app" or not app.is_dir():
        raise ValueError("Expected an existing built .app directory")
    with (app / "Info.plist").open("rb") as stream:
        info = plistlib.load(stream)
    bundle, executable = info.get("CFBundleIdentifier", ""), info.get("CFBundleExecutable", "")
    if not BUNDLE_RE.fullmatch(bundle) or not re.fullmatch(r"[A-Za-z0-9_.-]+", executable) or executable in (".", ".."):
        raise ValueError("Invalid built app bundle ID/executable")
    if "iPhoneSimulator" not in info.get("CFBundleSupportedPlatforms", []):
        raise ValueError("Built app is not an iPhoneSimulator product")
    if not (app / executable).is_file():
        raise ValueError("Built app executable is missing")
    return str(app), bundle, executable


def select_app(explicit=None):
    if explicit:
        return app_metadata(explicit)
    candidates = list((ROOT / ".local/ios-ci/Build/Products/Debug-iphonesimulator").glob("*.app"))
    if len(candidates) != 1:
        raise ValueError("Expected exactly one built simulator .app; pass an explicit app path")
    return app_metadata(candidates[0])


def parse_launch_pid(output, bundle):
    matches = re.findall(r"^" + re.escape(bundle) + r":[ \t]*([1-9][0-9]*)[ \t]*$", output, re.M)
    if len(matches) != 1 or not 0 < int(matches[0]) < 2**31:
        raise ValueError("simctl launch did not return one valid app PID")
    return int(matches[0])


def process_is_alive(output, bundle, pid):
    label_pattern = re.compile(r"(?:UIKitApplication:|application\.)" + re.escape(bundle) + r"(?:\[|\.|$)")
    for line in output.splitlines():
        columns = line.split()
        if len(columns) == 3 and columns[0] == str(pid) and label_pattern.match(columns[2]):
            return True
    return False


def owned_udid(state, inventory):
    """Never trust a stale UUID, prefix match, existing device or ambiguous name."""
    if state.get("schema") != 1 or state.get("name") != NAME_PREFIX + state.get("token", ""):
        raise ValueError("Invalid simulator ownership record")
    uuid.UUID(state["token"])
    if not RUNTIME_RE.fullmatch(state.get("runtime", "")) or not TYPE_RE.fullmatch(state.get("device_type", "")):
        raise ValueError("Invalid owned runtime/device type")
    prior = {canonical_udid(value) for value in state.get("prior_udids", [])}
    expected = canonical_udid(state["udid"]) if state.get("udid") else None
    if expected in prior:
        raise ValueError("Refusing to operate on a pre-existing simulator")
    matches = []
    for runtime, entry in devices(inventory):
        candidate = canonical_udid(entry["udid"])
        if entry.get("name") == state["name"] and runtime == state["runtime"]:
            if candidate in prior or (entry.get("deviceTypeIdentifier") and entry["deviceTypeIdentifier"] != state["device_type"]):
                raise ValueError("Simulator ownership did not match the creation record")
            matches.append(candidate)
        elif candidate == expected:
            raise ValueError("Owned UUID now refers to a different simulator")
    if len(matches) > 1 or (matches and expected is not None and matches[0] != expected):
        raise ValueError("Ambiguous simulator ownership")
    return matches[0] if matches else None


class Simctl:
    def __init__(self, artifacts):
        self.artifacts = artifacts

    def output(self, label, stdout, stderr):
        (self.artifacts / (label + ".stdout.log")).write_bytes(stdout or b"")
        (self.artifacts / (label + ".stderr.log")).write_bytes(stderr or b"")

    def call(self, args, label, timeout=60):
        return self._call(args, label, timeout)

    def call_env(self, args, label, env, timeout=60):
        """Only the explicit synthetic probe gate may be added to one owned app launch."""
        if not args or args[0] != "launch" or set(env) != {
            "SIMCTL_CHILD_BETTER_ZSLF_STARTUP_PROBE", "SIMCTL_CHILD_BETTER_ZSLF_PROBE_NAMESPACE"
        }:
            raise ValueError("Unexpected simulator probe environment")
        if env["SIMCTL_CHILD_BETTER_ZSLF_STARTUP_PROBE"] not in ("seed", "verify") or not re.fullmatch(
            r"[A-Za-z0-9_-]{1,80}", env["SIMCTL_CHILD_BETTER_ZSLF_PROBE_NAMESPACE"]
        ):
            raise ValueError("Invalid synthetic probe gate")
        return self._call(args, label, timeout, {**os.environ, **env})

    def _call(self, args, label, timeout, environment=None):
        command = ["xcrun", "simctl", *map(str, args)]
        save_json(self.artifacts / (label + ".command.json"), command)
        # Do not use Popen's context manager: its implicit wait has no timeout.
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, start_new_session=True, env=environment)
        grace = 0.25 if timeout <= 5 else 1.0
        try:
            stdout, stderr = process.communicate(timeout=timeout)
        except BaseException as error:
            stdout, stderr = getattr(error, "output", None), getattr(error, "stderr", None)
            try:
                os.killpg(process.pid, signal.SIGKILL)  # Only this newly owned command process group.
            except ProcessLookupError:
                pass
            try:
                stdout, stderr = process.communicate(timeout=grace)
            except subprocess.TimeoutExpired as drain:
                stdout, stderr = drain.output or stdout, drain.stderr or stderr
            finally:
                process.stdout.close()
                process.stderr.close()
                try:
                    process.wait(timeout=grace)
                except subprocess.TimeoutExpired:
                    # An uninterruptible child/escaped pipe holder must not block cleanup.
                    process.poll()
            self.output(label, stdout, stderr)
            raise
        else:
            process.stdout.close()
            process.stderr.close()
            self.output(label, stdout, stderr)
        if process.returncode:
            raise RuntimeError(f"simctl {args[0]} failed ({process.returncode}); see {label} logs")
        return stdout.decode("utf-8", errors="replace")


def png_dimensions(path):
    with path.open("rb") as stream:
        header = stream.read(24)
    if len(header) != 24 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        raise ValueError("simctl screenshot did not produce a PNG")
    dimensions = struct.unpack(">II", header[16:24])
    if min(dimensions) <= 0:
        raise ValueError("Simulator screenshot is empty")
    return dimensions


def smoke(artifacts, app, runner=None, sleep=time.sleep, keychain_diagnostics=False):
    runner = runner or Simctl(artifacts)
    if (artifacts / "owner.json").exists():
        raise ValueError("Ownership directory must be fresh for this invocation")
    app_path, bundle, executable = app_metadata(app)
    inventory = json.loads(runner.call(["list", "--json"], "inventory-before", 30))
    save_json(artifacts / "inventory-before.json", inventory)
    runtime, device_type = choose_destination(inventory)
    token = str(uuid.uuid4())
    state = dict(schema=1, token=token, name=NAME_PREFIX + token, runtime=runtime,
                 device_type=device_type, prior_udids=sorted(all_udids(inventory)),
                 app=app_path, bundle=bundle, executable=executable, udid=None, pid=None,
                 booted=False, success=False, create_attempted=True, creation_confirmed=False)
    save_json(artifacts / "owner.json", state)  # Persist BEFORE invoking create.
    created = canonical_udid(runner.call(["create", state["name"], device_type, runtime], "create", 60).strip())
    after = json.loads(runner.call(["list", "devices", "--json"], "inventory-created", 30))
    claimed = owned_udid({**state, "udid": created}, after)
    if claimed != created:
        raise ValueError("New simulator ownership was not confirmed")
    state["udid"] = created
    state["creation_confirmed"] = True
    save_json(artifacts / "owner.json", state)
    runner.call(["boot", created], "boot", 60)
    runner.call(["bootstatus", created, "-b"], "bootstatus", 240)
    state["booted"] = True
    save_json(artifacts / "owner.json", state)
    keychain_evidence = None
    if keychain_diagnostics:
        from ios_keychain_diagnostics import run_diagnostics

        def update_pid(pid):
            state["pid"] = pid
            save_json(artifacts / "owner.json", state)

        keychain_evidence = run_diagnostics(artifacts, app_path, bundle, created, runner, update_pid, sleep=sleep)
        state["pid"] = keychain_evidence["pid"]
    else:
        runner.call(["install", created, app_path], "install", 90)
        state["pid"] = parse_launch_pid(runner.call(["launch", created, bundle], "launch", 60), bundle)
    save_json(artifacts / "owner.json", state)
    for index in range(3):
        if index:
            sleep(3)
        processes = runner.call(["spawn", created, "launchctl", "list"], f"process-{index}", 30)
        if not process_is_alive(processes, bundle, state["pid"]):
            raise RuntimeError("Launched app exited/restarted during the startup stability window")
    screenshot = artifacts / "startup.png"
    runner.call(["io", created, "screenshot", str(screenshot)], "screenshot", 30)
    dimensions = png_dimensions(screenshot)
    final = runner.call(["spawn", created, "launchctl", "list"], "process-after-screenshot", 30)
    if not process_is_alive(final, bundle, state["pid"]):
        raise RuntimeError("App exited immediately after its startup screenshot")
    state["success"] = True
    save_json(artifacts / "owner.json", state)
    result = dict(result="startup-smoke-passed", udid=created, pid=state["pid"], bundle=bundle,
                  runtime=runtime, device_type=device_type, screenshot=screenshot.name,
                  screenshot_dimensions=dimensions, stability_seconds=6,
                  coverage="Install/launch/PID liveness/screenshot only; image needs manual review, not full functionality")
    if keychain_evidence is not None:
        result.update(result="startup-keychain-smoke-passed", keychain_diagnostics=keychain_evidence,
                      login_ui_verified=False,
                      coverage="Synthetic Keychain CRUD/empty/namespace/restart/delete and real Graph initialization; screenshot still needs manual Login review; no credentials, HTTP or permission input")
    save_json(artifacts / "result.json", result)
    return result


def cleanup(artifacts, runner=None, fast=False, sleep=time.sleep, clock=time.monotonic):
    runner = runner or Simctl(artifacts)
    owner = artifacts / "owner.json"
    if not owner.exists():
        return
    state = json.loads(owner.read_text(encoding="utf-8"))
    pending = state.get("create_attempted") and not state.get("creation_confirmed")
    # Normal inventory may retry within 24s, leaving >90s of the existing 120s cleanup
    # reserve for logs/terminate/shutdown/delete. Signal cleanup retains its 2s budget.
    deadline = clock() + (2.0 if fast else 24.0)
    report = dict(udid=None, actions=[], deleted=False, recovery_attempts=0, inventory_errors=[])
    udid = None
    inventory_observed = False
    for attempt in range(5 if fast else 6):
        remaining = deadline - clock()
        if remaining <= 0:
            break
        label = "cleanup-inventory" if attempt == 0 else f"cleanup-inventory-{attempt}"
        report["recovery_attempts"] += 1
        try:
            inventory = json.loads(runner.call(["list", "devices", "--json"], label,
                                              min(0.5 if fast else 8.0, remaining)))
        except subprocess.TimeoutExpired:
            report["inventory_errors"].append({"attempt": attempt + 1, "type": "TimeoutExpired"})
            save_json(artifacts / "cleanup.json", report)
            continue
        except Exception as error:
            report["uncertain"] = True
            report["inventory_errors"].append({"attempt": attempt + 1, "type": type(error).__name__})
            save_json(artifacts / "cleanup.json", report)
            raise
        try:
            udid = owned_udid(state, inventory)
        except Exception as error:
            report["uncertain"] = True
            report["ownership_error"] = type(error).__name__
            save_json(artifacts / "cleanup.json", report)
            raise
        inventory_observed = True
        if udid is not None or not pending:
            break
        if attempt < (4 if fast else 5):
            sleep(min(0.15 if fast else 1.0, max(0, deadline - clock())))
    report["udid"] = udid
    if udid is None:
        # Neither timeout nor a killed create client proves that the owned device is absent.
        report["uncertain"] = bool(pending) or not inventory_observed
        report["deleted"] = not report["uncertain"]
        save_json(artifacts / "cleanup.json", report)
        if not inventory_observed:
            raise RuntimeError("Owned simulator inventory unavailable after bounded timeout recovery; cleanup uncertain")
        if pending:
            raise RuntimeError("Interrupted create outcome remains uncertain after bounded exact-name recovery")
        return
    # Recover an interrupted create by exact unique name/runtime/type, never a prefix.
    state["udid"] = udid
    state["creation_confirmed"] = True
    save_json(owner, state)
    if not BUNDLE_RE.fullmatch(state.get("bundle", "")) or not re.fullmatch(r"[A-Za-z0-9_.-]+", state.get("executable", "")):
        raise ValueError("Invalid cleanup app identity")
    if state.get("booted") and not fast:
        try:
            runner.call(["spawn", udid, "log", "show", "--style", "compact", "--last", "2m",
                         "--predicate", f'process == "{state["executable"]}"'], "app-log", 10)
        except Exception as error:
            report["log_error"] = str(error)
        if not state.get("success"):
            try:
                runner.call(["io", udid, "screenshot", str(artifacts / "failure.png")], "failure-screenshot", 5)
            except Exception as error:
                report["screenshot_error"] = str(error)
    # Signal cleanup reserves its short grace for shutdown/delete, not diagnostics.
    actions = [] if fast else [("terminate", ["terminate", udid, state["bundle"]], 10)]
    actions += [("shutdown", ["shutdown", udid], 1.5 if fast else 20),
                ("delete", ["delete", udid], 1.5 if fast else 20)]
    delete_error = None
    for action, args, timeout in actions:
        try:
            runner.call(args, "cleanup-" + action, timeout)
            report["actions"].append({"action": action, "ok": True})
            if action == "delete":
                report["deleted"] = True
        except Exception as error:
            report["actions"].append({"action": action, "error": str(error)})
            if action == "delete":
                delete_error = error
    save_json(artifacts / "cleanup.json", report)
    if delete_error:
        raise RuntimeError("Owned simulator deletion failed; see cleanup diagnostics") from delete_error


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("smoke", "cleanup"))
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--app", type=Path)
    parser.add_argument("--fast", action="store_true", help="Skip diagnostics during signal cleanup")
    parser.add_argument("--keychain-diagnostics", action="store_true", help="Explicit debug-Simulator-only synthetic Keychain/signing A-B path")
    args = parser.parse_args()
    if sys.platform != "darwin":
        raise SystemExit("Native simulator smoke requires macOS; use offline fixture unit tests elsewhere")
    if not args.artifacts.is_dir():
        raise SystemExit("Artifact directory must be created by the shell owner")
    if args.mode == "cleanup" and args.fast:
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
    else:
        signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    if args.mode == "smoke":
        def deadline_expired(*_):
            raise TimeoutError("Startup exceeded 600 seconds; reserve the remaining CI time for owned cleanup")
        signal.signal(signal.SIGALRM, deadline_expired)
        signal.alarm(600)  # 10-minute body inside the 12-minute step; cleanup has 2 minutes.
        try:
            app, _, _ = select_app(args.app)
            print(json.dumps(smoke(args.artifacts.resolve(), app, keychain_diagnostics=args.keychain_diagnostics), indent=2))
        except Exception as error:
            save_json(args.artifacts / "error.json", {"result": "startup-smoke-failed", "error": str(error)})
            raise
        finally:
            signal.alarm(0)
    else:
        cleanup(args.artifacts.resolve(), fast=args.fast)


if __name__ == "__main__":
    main()
