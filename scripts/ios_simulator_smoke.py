#!/usr/bin/env python3
"""Startup-only iOS Simulator smoke. No credentials, UI input or runtime downloads.

ci-ios-smoke.sh owns the EXIT/signal trap. This helper persists ownership before
create so cleanup can recover a create whose stdout was lost. All mutation and
spawn commands require the exact newly created UUID; never 'booted' or 'all'.
"""
import argparse
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import plistlib
import re
import signal
import struct
import subprocess
import sys
import tempfile
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


def choose_destination(inventory, requested_runtime=None):
    """Pure installed/available selection; an exact optional pin never falls back."""
    if requested_runtime is not None and (type(requested_runtime) is not str or not RUNTIME_RE.fullmatch(requested_runtime)):
        raise ValueError("Requested runtime must be a canonical iOS runtime identifier")
    types = [entry for entry in inventory.get("devicetypes", [])
             if TYPE_RE.fullmatch(entry.get("identifier", "")) and
             (entry.get("productFamily") in ("iPhone", "iPad") or
              entry.get("name", "").startswith(("iPhone", "iPad")))]
    if requested_runtime is not None:
        runtimes = [entry for entry in inventory.get("runtimes", []) if entry.get("identifier") == requested_runtime]
        if len(runtimes) != 1 or runtimes[0].get("isAvailable") is not True:
            raise ValueError("Requested runtime is not uniquely installed and available; no fallback or download")
    else:
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
    if requested_runtime is not None:
        raise ValueError("Requested runtime has no compatible installed device type; no fallback or download")
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


def parse_launchctl_table(output):
    """Absence needs a complete successful process table, not empty/partial stdout."""
    if not isinstance(output, str) or not output.endswith("\n"):
        raise ValueError("Incomplete launchctl process table")
    rows = [line.split() for line in output.splitlines() if line.strip()]
    if len(rows) < 2 or rows[0] != ["PID", "Status", "Label"]:
        raise ValueError("Missing launchctl process table header or entries")
    for columns in rows[1:]:
        if (len(columns) != 3 or not re.fullmatch(r"-|[1-9][0-9]*", columns[0])
                or not re.fullmatch(r"-?[0-9]+", columns[1])):
            raise ValueError("Malformed launchctl process table entry")
    return rows[1:]


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


class SmokeDeadlineExceeded(BaseException):
    """One-shot body watchdog: never tolerate it as an operational app error."""
    smoke_deadline_expired = True


def is_smoke_deadline(error):
    # The CLI may be __main__ while diagnostics imports this helper by module name.
    return getattr(error, "smoke_deadline_expired", False) is True


def begin_command_result(label, timeout):
    return {"schema": 1, "label": label, "budget_seconds": timeout,
            "started_at_utc": datetime.now(timezone.utc).isoformat(), "started_monotonic": time.monotonic(),
            "outcome": "running", "ok": False, "returncode": None, "timed_out": False,
            "partial": True, "drain_complete": False,
            "stdout_file": label + ".stdout.log", "stderr_file": label + ".stderr.log"}


def finish_command_result(record, stdout, stderr):
    record["finished_at_utc"] = datetime.now(timezone.utc).isoformat()
    record["finished_monotonic"] = time.monotonic()
    record["elapsed_seconds"] = record["finished_monotonic"] - record["started_monotonic"]
    record["stdout_bytes"] = len(stdout or b"")
    record["stderr_bytes"] = len(stderr or b"")
    # EOF after killing a timed-out task is not a complete successful response.
    record["partial"] = (record["timed_out"] or not record["drain_complete"] or
                         record.get("truncated", False) or "evidence_error_type" in record)


class Simctl:
    def __init__(self, artifacts):
        self.artifacts = artifacts

    def output(self, label, stdout, stderr):
        (self.artifacts / (label + ".stdout.log")).write_bytes(stdout or b"")
        (self.artifacts / (label + ".stderr.log")).write_bytes(stderr or b"")

    def _persist_result(self, label, record, stdout, stderr, secondary_error):
        # Every step is best-effort independently; a late save must not mask a primary timeout.
        for operation, action in (
            ("write-output", lambda: self.output(label, stdout, stderr)),
            ("finish-result", lambda: finish_command_result(record, stdout, stderr)),
            ("save-result", lambda: save_json(self.artifacts / (label + ".result.json"), record)),
        ):
            try:
                action()
            except BaseException as error:
                record.setdefault("evidence_error_type", type(error).__name__)
                secondary_error(operation, error)

    @staticmethod
    def _poll_before_cleanup(record, process, secondary_error):
        poll = {"observed_at_utc": None, "observed_monotonic": None,
                "state": "unknown", "returncode": None}
        record["pre_cleanup_poll"] = poll
        try:
            poll["observed_at_utc"] = datetime.now(timezone.utc).isoformat()
            poll["observed_monotonic"] = time.monotonic()
            code = process.poll()
            if type(code) is int:
                poll.update(state="exited", returncode=code)
            elif code is None:
                poll["state"] = "running"
        except BaseException as error:
            poll["error_type"] = type(error).__name__
            secondary_error("pre-cleanup-poll", error)

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

    def call_host(self, kind, label, timeout):
        """One fixed, read-only host observation; no argv/env collection or fallback."""
        commands = {"ps": ["/bin/ps", "-Aww", "-o", "pid=,ppid=,comm="],
                    "vm_stat": ["/usr/bin/vm_stat"]}
        if type(kind) is not str or kind not in commands:
            raise ValueError("Unknown fixed host observation")
        if type(timeout) not in (int, float) or not 0 < timeout <= 3:
            raise ValueError("Host observation timeout must be positive and at most 3 seconds")
        if type(label) is not str or not re.fullmatch(r"[A-Za-z0-9_.-]+", label) or label in (".", ".."):
            raise ValueError("Invalid host observation label")
        command = commands[kind]
        save_json(self.artifacts / (label + ".command.json"), command)
        record = begin_command_result(label, timeout)
        limits = {"stdout": 256 * 1024, "stderr": 16 * 1024}
        record.update(execution_context="host", host_kind=kind, capture_mode="file-backed-prefix",
                      truncated=False, **{name + "_limit_bytes": limit for name, limit in limits.items()})
        save_json(self.artifacts / (label + ".result.json"), record)
        deadline = record["started_monotonic"] + timeout
        reserve = min(0.25, timeout / 4)
        process = None
        streams = {"stdout": None, "stderr": None}
        stdout, stderr, text = b"", b"", None
        primary_error = cleanup_interrupt = None
        wait_finished = False

        def latch(error):
            record["error_type"] = type(error).__name__
            record["timed_out"] = isinstance(error, subprocess.TimeoutExpired) or is_smoke_deadline(error)
            record["ok"] = False
            if record["outcome"] in ("running", "success"):
                record["outcome"] = "timeout" if record["timed_out"] else "error"

        def secondary_error(operation, error):
            nonlocal cleanup_interrupt
            record.setdefault("cleanup_errors", []).append({"operation": operation, "error_type": type(error).__name__})
            if is_smoke_deadline(error):
                record["body_deadline_expired"] = True
                if primary_error is not None:
                    primary_error.smoke_deadline_expired = True
                elif cleanup_interrupt is not None:
                    cleanup_interrupt.smoke_deadline_expired = True
            if primary_error is None and cleanup_interrupt is None:
                cleanup_interrupt = error
                latch(error)

        def evidence_error(operation, error):
            record.setdefault("evidence_error_type", type(error).__name__)
            secondary_error(operation, error)

        try:
            if sys.platform != "darwin":
                raise OSError("Fixed host observations require macOS")
            for name in streams:
                streams[name] = tempfile.TemporaryFile(mode="w+b")
            if deadline - time.monotonic() <= reserve:
                raise subprocess.TimeoutExpired(command, timeout)
            process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=streams["stdout"],
                                       stderr=streams["stderr"], start_new_session=True)
            remaining = deadline - time.monotonic() - reserve
            if remaining <= 0:
                raise subprocess.TimeoutExpired(command, timeout)
            process.wait(timeout=remaining)
            wait_finished = True
            code = process.returncode
            if type(code) is not int:
                raise RuntimeError("Host observation returned no exact exit status")
            record["returncode"] = code
            record["drain_complete"] = True
            if code:
                record["outcome"] = "nonzero"
                raise RuntimeError("Fixed host observation failed; see " + label + " logs")
        except BaseException as error:
            primary_error = error
            latch(error)
            if process is not None and not wait_finished:
                self._poll_before_cleanup(record, process, secondary_error)
                try:
                    os.killpg(process.pid, signal.SIGKILL)  # Only this owned host child's session.
                except BaseException as cleanup:
                    secondary_error("kill-owned-host-command", cleanup)
                try:
                    remaining = max(0, deadline - time.monotonic())
                    process.wait(timeout=remaining)
                    record["drain_complete"] = True
                except BaseException as cleanup:
                    record["wait_error_type"] = type(cleanup).__name__
                    secondary_error("wait-owned-host-command", cleanup)
                try:
                    code = process.returncode
                    record["post_cleanup_returncode"] = code if type(code) is int else None
                except BaseException as cleanup:
                    record["post_cleanup_returncode"] = None
                    secondary_error("cleanup-returncode", cleanup)
        finally:
            captured = {}
            for name, stream in streams.items():
                captured[name] = b""
                record[name + "_total_bytes"] = None
                record[name + "_truncated"] = False
                if stream is not None:
                    try:
                        size = stream.seek(0, os.SEEK_END)
                        stream.seek(0)
                        captured[name] = stream.read(limits[name])
                        record[name + "_total_bytes"] = size
                        record[name + "_truncated"] = size > limits[name]
                        record["truncated"] |= record[name + "_truncated"]
                    except BaseException as error:
                        evidence_error("read-" + name, error)
                    finally:
                        try:
                            stream.close()
                        except BaseException as error:
                            evidence_error("close-" + name, error)
            stdout, stderr = captured["stdout"], captured["stderr"]
            if primary_error is None and cleanup_interrupt is None:
                if record["truncated"]:
                    record["outcome"] = "truncated"
                    secondary_error("bounded-capture", ValueError("Host observation exceeded bounded output coverage"))
                else:
                    try:
                        text = stdout.decode("utf-8")
                    except BaseException as error:
                        secondary_error("decode-output", error)
                    else:
                        record.update(outcome="success", ok=True)
            self._persist_result(label, record, stdout, stderr, evidence_error)
            if primary_error is None and cleanup_interrupt is not None:
                raise cleanup_interrupt
        if primary_error is not None:
            raise primary_error
        return text

    def _call(self, args, label, timeout, environment=None):
        command = ["xcrun", "simctl", *map(str, args)]
        save_json(self.artifacts / (label + ".command.json"), command)
        record = begin_command_result(label, timeout)
        save_json(self.artifacts / (label + ".result.json"), record)
        process = None
        stdout, stderr = b"", b""
        grace = 0.25 if timeout <= 5 else 1.0
        primary_error = None
        cleanup_interrupt = None

        def latch(error):
            record["error_type"] = type(error).__name__
            record["timed_out"] = isinstance(error, subprocess.TimeoutExpired) or is_smoke_deadline(error)
            record["ok"] = False
            if record["outcome"] in ("running", "success"):
                record["outcome"] = "timeout" if record["timed_out"] else "error"

        def cleanup_error(operation, error):
            nonlocal cleanup_interrupt
            record.setdefault("cleanup_errors", []).append({"operation": operation, "error_type": type(error).__name__})
            if is_smoke_deadline(error):
                record["body_deadline_expired"] = True
                if primary_error is not None:
                    primary_error.smoke_deadline_expired = True
                elif cleanup_interrupt is not None:
                    cleanup_interrupt.smoke_deadline_expired = True
            if primary_error is None and cleanup_interrupt is None:
                cleanup_interrupt = error
                latch(error)

        def close_pipes():
            if process is not None:
                for name in ("stdout", "stderr"):
                    try:
                        getattr(process, name).close()
                    except BaseException as error:
                        cleanup_error("close-" + name, error)

        try:
            # No Popen context manager: its implicit wait is unbounded.
            process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                       stderr=subprocess.PIPE, start_new_session=True, env=environment)
            try:
                stdout, stderr = process.communicate(timeout=timeout)
                record["drain_complete"] = True
            except BaseException as error:
                primary_error = error
                latch(error)  # Latch before any cleanup can itself fail.
                stdout, stderr = getattr(error, "output", None), getattr(error, "stderr", None)
                self._poll_before_cleanup(record, process, cleanup_error)
                try:
                    os.killpg(process.pid, signal.SIGKILL)  # Only this command's owned process group.
                except BaseException as error:
                    cleanup_error("kill-owned-command", error)
                try:
                    stdout, stderr = process.communicate(timeout=grace)
                    record["drain_complete"] = True
                except BaseException as drain:
                    stdout = getattr(drain, "output", None) or stdout
                    stderr = getattr(drain, "stderr", None) or stderr
                    record["drain_error_type"] = type(drain).__name__
                    cleanup_error("drain", drain)
                finally:
                    close_pipes()
                    try:
                        process.wait(timeout=grace)
                    except BaseException as wait_error:
                        record["wait_error_type"] = type(wait_error).__name__
                        cleanup_error("wait", wait_error)
                        try:
                            process.poll()
                        except BaseException as poll_error:
                            cleanup_error("poll", poll_error)
                    try:
                        code = process.returncode
                        record["post_cleanup_returncode"] = code if type(code) is int else None
                    except BaseException as error:
                        record["post_cleanup_returncode"] = None
                        cleanup_error("cleanup-returncode", error)
                raise  # Preserve the original timeout/interrupt even if drain returned zero.
            record["returncode"] = process.returncode
            if process.returncode:
                record["outcome"] = "nonzero"
                raise RuntimeError(f"simctl {args[0]} failed ({process.returncode}); see {label} logs")
            record.update(outcome="success", ok=True)
            return stdout.decode("utf-8", errors="replace")
        except BaseException as error:
            if primary_error is None:
                primary_error = error
                latch(error)
            elif error is not primary_error:
                cleanup_error("secondary-command-error", error)
                raise primary_error
            raise
        finally:
            close_pipes()
            def evidence_error(operation, error):
                nonlocal cleanup_interrupt
                cleanup_error(operation, error)
                if primary_error is None and cleanup_interrupt is None:
                    cleanup_interrupt = error
                    latch(error)
            self._persist_result(label, record, stdout, stderr, evidence_error)
            if primary_error is None and cleanup_interrupt is not None:
                raise cleanup_interrupt


def png_dimensions(path):
    with path.open("rb") as stream:
        header = stream.read(24)
    if len(header) != 24 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        raise ValueError("simctl screenshot did not produce a PNG")
    dimensions = struct.unpack(">II", header[16:24])
    if min(dimensions) <= 0:
        raise ValueError("Simulator screenshot is empty")
    return dimensions


def exported_png_is_valid(artifacts, filename):
    """Validate the successful helper's filename, not an unrelated timeout leftover."""
    if not isinstance(filename, str) or Path(filename).name != filename or not filename.endswith(".png"):
        return False
    path = artifacts / filename
    if path.is_symlink() or not path.is_file():
        return False
    try:
        png_dimensions(path)
    except (OSError, ValueError):
        return False
    return True


def smoke(artifacts, app, runner=None, sleep=time.sleep, keychain_diagnostics=False, diagnostic_mode="ab",
          signing_method="manual-ad-hoc", body_deadline=None, requested_runtime=None):
    from ios_keychain_diagnostics import validate_signing_method
    validate_signing_method(signing_method, diagnostic_mode)
    if diagnostic_mode not in ("ab", "signed-positive"):
        raise ValueError("Expected ab or signed-positive diagnostic mode")
    if diagnostic_mode != "ab" and not keychain_diagnostics:
        raise ValueError("signed-positive requires the explicit Keychain diagnostic gate")
    runner = runner or Simctl(artifacts)
    if body_deadline is not None:
        if type(body_deadline) not in (int, float) or not math.isfinite(body_deadline):
            raise ValueError("Existing body deadline must be a finite monotonic time")
        runner.body_deadline_monotonic = body_deadline  # Read-only observers share the original 600s cutoff.
    if (artifacts / "owner.json").exists():
        raise ValueError("Ownership directory must be fresh for this invocation")
    app_path, bundle, executable = app_metadata(app)
    # CI8: initial cold read timed out before any owned device existed. Retry only
    # TimeoutExpired once, never nonzero output/bad JSON, without touching services/devices.
    for attempt in range(2):
        label = "inventory-before" + ("-retry" if attempt else "")
        try:
            output = runner.call(["list", "--json"], label, 30)
            break
        except subprocess.TimeoutExpired:
            if attempt:
                raise
    inventory = json.loads(output)
    save_json(artifacts / "inventory-before.json", inventory)
    runtime, device_type = choose_destination(inventory, requested_runtime=requested_runtime)
    token = str(uuid.uuid4())
    state = dict(schema=1, token=token, name=NAME_PREFIX + token, runtime=runtime,
                 device_type=device_type, prior_udids=sorted(all_udids(inventory)),
                 app=app_path, bundle=bundle, executable=executable, udid=None, pid=None,
                 booted=False, success=False, create_attempted=True, creation_confirmed=False,
                 diagnostic_mode=diagnostic_mode if keychain_diagnostics else None, signing_method=signing_method)
    save_json(artifacts / "owner.json", state)  # Persist BEFORE invoking create.
    if keychain_diagnostics:
        (artifacts / "README.md").write_text(
            "# Owned Simulator startup diagnostics\n\nExperiment: " + diagnostic_mode + "\nMethod: " + signing_method + "\n\n" +
            ("Xcode Simulator packaging is a new multi-condition build, not byte-identical/single-key attribution.\n\n"
             if signing_method == "xcode-simulator" else "") +
            ("A: not_run / historical-negative-only (CI7/CI9/CI10), not an A pass. "
             "Independent fresh-device signed positive; not single-factor signature/LS/resource attribution.\n\n"
             if diagnostic_mode == "signed-positive" else
             "Original AB: A evidence/absence gate remains mandatory before one owned-app clean reinstall.\n\n") +
            "B seed/verify uses one signed installation and namespace, real PID restart and Native Keychain/Graph checks. "
            "No credentials or HTTP input; native offline gate remains enabled. "
            "Native checks/PID/screenshot are not verified Login: inspect startup.png manually. "
            "The input app product is unchanged; previous CI12 artifacts are separate historical evidence.\n", encoding="utf-8")
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

        keychain_evidence = run_diagnostics(artifacts, app_path, bundle, created, runner, update_pid,
                                            sleep=sleep, mode=diagnostic_mode, signing_method=signing_method)
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
                      experiment_mode=diagnostic_mode, signing_method=signing_method, login_ui_verified=False, ui_review="pending",
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
    parser.add_argument("--runtime", help="Smoke only: exact installed/available iOS runtime ID; no fallback or download")
    parser.add_argument("--fast", action="store_true", help="Skip diagnostics during signal cleanup")
    parser.add_argument("--keychain-diagnostics", action="store_true", help="Explicit debug-Simulator-only Keychain diagnostics")
    parser.add_argument("--diagnostic-mode", choices=("ab", "signed-positive"),
                        default=os.environ.get("BETTER_ZSLF_CI_DIAGNOSTIC_MODE", "ab"),
                        help="Default AB is unchanged; CI may explicitly select independent signed-positive")
    parser.add_argument("--signing-method", choices=("manual-ad-hoc", "xcode-simulator"),
                        default=os.environ.get("BETTER_ZSLF_CI_SIGNING_METHOD", "manual-ad-hoc"),
                        help="Explicit shared build/smoke method; historical AB stays manual")
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
            raise SmokeDeadlineExceeded("Startup exceeded 600 seconds; reserve the remaining CI time for owned cleanup")
        signal.signal(signal.SIGALRM, deadline_expired)
        body_deadline = time.monotonic() + 600
        signal.alarm(600)  # 10-minute body inside the 12-minute step; cleanup has 2 minutes.
        try:
            app, _, _ = select_app(args.app)
            print(json.dumps(smoke(args.artifacts.resolve(), app, keychain_diagnostics=args.keychain_diagnostics,
                                   diagnostic_mode=args.diagnostic_mode, signing_method=args.signing_method,
                                   body_deadline=body_deadline, requested_runtime=getattr(args, "runtime", None)), indent=2))
        except (Exception, SmokeDeadlineExceeded) as error:
            save_json(args.artifacts / "error.json", {"result": "startup-smoke-failed", "error": str(error)})
            raise
        finally:
            signal.alarm(0)
    else:
        cleanup(args.artifacts.resolve(), fast=args.fast)


if __name__ == "__main__":
    main()
