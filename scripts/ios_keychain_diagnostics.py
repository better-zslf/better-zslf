#!/usr/bin/env python3
"""Owned-Simulator unsigned/ad-hoc A/B evidence; no account or certificate access.

Only the Debug-iphonesimulator product is copied and signed. Native report fields
are an allowlist, not a source of paths, commands, credentials, or inferred status
codes. A successful signed seed/verify does not turn missing unsigned evidence
into a pass. The smoke owner remains responsible for device ownership/cleanup
and the final PID stability window/startup.png.
"""
import argparse
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import stat
import subprocess
import sys
import time
import uuid

REPORT_RELATIVE = Path("Library/Caches/better-zslf-ci-startup.json")
MAX_REPORT_BYTES = 64 * 1024
REPORT_TIMEOUT = 30.0
COMMAND_TIMEOUT = 5
POLL_INTERVAL = 0.5
TEAM_ID = "CIKEYCHAIN"  # Synthetic ten-letter CI fixture; no developer account.
BUNDLE_RE = re.compile(r"[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)+")
EXECUTABLE_RE = re.compile(r"[A-Za-z0-9_.-]+")
EVENT_RE = re.compile(r"[A-Z_]{1,64}")
REPORT_FIELDS = {"schema", "phase", "probeComplete", "probeOk", "graphComplete",
                 "graphOk", "sessionAbsent", "events"}
MACHO_MAGICS = {b"\xfe\xed\xfa\xce", b"\xce\xfa\xed\xfe", b"\xfe\xed\xfa\xcf",
                b"\xcf\xfa\xed\xfe", b"\xca\xfe\xba\xbe", b"\xbe\xba\xfe\xca",
                b"\xca\xfe\xba\xbf", b"\xbf\xba\xfe\xca"}


class ReportError(ValueError):
    """A fixed native evidence file failed its size/path/schema contract."""


def _save_json(path, value):
    path = Path(path)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
    temporary.replace(path)


def _error(error):
    # Never include native report contents or command output in public errors.
    return {"type": type(error).__name__, "message": str(error)}


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ReportError("Native report has a duplicate JSON field")
        result[key] = value
    return result


def _invalid_constant(_):
    raise ReportError("Native report contains a non-JSON scalar")


def validate_report(value, phase):
    """Validate the complete allowlist, including partial initialization reports."""
    if phase not in ("seed", "verify"):
        raise ValueError("Expected native seed or verify phase")
    if type(value) is not dict or set(value) != REPORT_FIELDS:
        raise ReportError("Native report fields do not match schema 1")
    if type(value["schema"]) is not int or value["schema"] != 1 or value["phase"] != phase:
        raise ReportError("Native report schema/phase mismatch")
    for field in ("probeComplete", "probeOk", "graphComplete", "graphOk"):
        if type(value[field]) is not bool:
            raise ReportError("Native report completion/check fields must be booleans")
    if value["sessionAbsent"] is not None and type(value["sessionAbsent"]) is not bool:
        raise ReportError("Native report sessionAbsent must be boolean or null")
    events = value["events"]
    if type(events) is not list or len(events) > 256:
        raise ReportError("Native report event list exceeds its allowlist")
    for event in events:
        if type(event) is not dict or set(event) not in ({"stage", "result"}, {"stage", "result", "status"}):
            raise ReportError("Native report event fields exceed their allowlist")
        if any(type(event[key]) is not str or not EVENT_RE.fullmatch(event[key]) for key in ("stage", "result")):
            raise ReportError("Native report event tokens must be uppercase letters/underscores")
        if "status" in event and (type(event["status"]) is not int or not -(2**31) <= event["status"] < 2**31):
            raise ReportError("Native report status must be a signed 32-bit integer")
    return value


def _report_path(container):
    """Resolve only the fixed relative native path, rejecting link traversal."""
    container = Path(container)
    if not container.is_absolute() or ".." in container.parts or container.is_symlink() or not container.is_dir():
        raise ReportError("App data container must be an absolute non-symlink directory")
    root = container.resolve(strict=True)
    current = container
    for component in REPORT_RELATIVE.parts:
        current = current / component
        if current.is_symlink():
            raise ReportError("Native report path must not contain symlinks")
        if current != container / REPORT_RELATIVE and current.exists() and not current.is_dir():
            raise ReportError("Native report parent must be a directory")
    if not current.resolve(strict=False).is_relative_to(root):
        raise ReportError("Native report escaped its data container")
    return current


def read_native_report(container, phase):
    """Return None for a missing file; all present malformed files fail closed."""
    path = _report_path(container)
    try:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0))
    except FileNotFoundError:
        return None
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode):
            raise ReportError("Native report must be a regular file")
        if not 0 < metadata.st_size <= MAX_REPORT_BYTES:
            raise ReportError("Native report is empty or exceeds 64 KiB")
        with os.fdopen(descriptor, "rb", closefd=False) as stream:
            raw = stream.read(MAX_REPORT_BYTES + 1)
        if not 0 < len(raw) <= MAX_REPORT_BYTES:
            raise ReportError("Native report is empty or exceeds 64 KiB")
    finally:
        os.close(descriptor)
    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_object, parse_constant=_invalid_constant)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ReportError("Native report is not valid UTF-8 JSON") from error
    return validate_report(value, phase)


def _clear_report(container):
    path = _report_path(container)
    try:
        metadata = path.lstat()
    except FileNotFoundError:
        return False
    if not stat.S_ISREG(metadata.st_mode):
        raise ReportError("Stale native report must be a regular non-symlink file")
    path.unlink()  # Only this fixed owned app-data file, before each launch.
    return True


def _source_app(app_path, bundle=None):
    source = Path(app_path)
    if source.is_symlink():
        raise ValueError("Source app must not be a symlink")
    source = source.resolve(strict=True)
    if source.suffix != ".app" or not source.is_dir() or source.parent.name != "Debug-iphonesimulator":
        raise ValueError("Signing is limited to an existing Debug-iphonesimulator .app product")
    info_path = source / "Info.plist"
    if info_path.is_symlink():
        raise ValueError("Source app Info.plist must not be a symlink")
    with info_path.open("rb") as stream:
        info = plistlib.load(stream)
    if type(info) is not dict:
        raise ValueError("Source Info.plist must be a dictionary")
    platforms = info.get("CFBundleSupportedPlatforms")
    if type(platforms) is not list or "iPhoneSimulator" not in platforms or "iPhoneOS" in platforms:
        raise ValueError("Source app is not an iPhoneSimulator product")
    if "DTPlatformName" in info and info["DTPlatformName"] != "iphonesimulator":
        raise ValueError("DTPlatformName must be iphonesimulator when present")
    actual = info.get("CFBundleIdentifier")
    executable = info.get("CFBundleExecutable")
    if type(actual) is not str or not BUNDLE_RE.fullmatch(actual) or (bundle is not None and bundle != actual):
        raise ValueError("Source app bundle identity mismatch")
    if type(executable) is not str or not EXECUTABLE_RE.fullmatch(executable) or executable in (".", ".."):
        raise ValueError("Invalid source app executable")
    if not (source / executable).is_file() or (source / executable).is_symlink():
        raise ValueError("Source app executable is missing or linked")
    return source, actual, executable


def _is_macho(path):
    with Path(path).open("rb") as stream:
        return stream.read(4) in MACHO_MAGICS


def _bytes(value):
    if value is None:
        return b""
    return value.encode("utf-8") if isinstance(value, str) else value


def _codesign(command, evidence, label, run):
    """Persist command/stdout/stderr even on a nonzero exit, timeout or interrupt."""
    _save_json(evidence / (label + ".command.json"), command)
    try:
        process = run(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                      timeout=60, check=False)
    except BaseException as error:
        (evidence / (label + ".stdout.log")).write_bytes(_bytes(getattr(error, "stdout", getattr(error, "output", None))))
        (evidence / (label + ".stderr.log")).write_bytes(_bytes(getattr(error, "stderr", None)))
        _save_json(evidence / (label + ".result.json"), {"ok": False, "error_type": type(error).__name__})
        raise
    (evidence / (label + ".stdout.log")).write_bytes(_bytes(process.stdout))
    (evidence / (label + ".stderr.log")).write_bytes(_bytes(process.stderr))
    _save_json(evidence / (label + ".result.json"), {"ok": process.returncode == 0, "returncode": process.returncode})
    if process.returncode:
        raise RuntimeError(f"CI simulator codesign failed ({process.returncode}); see {label} logs")


def sign_simulator_copy(artifacts, app_path, bundle=None, *, run=None):
    """Return the signed copy Path; never codesign or alter the original product.

    `run` is a subprocess.run-compatible offline test seam. Production signing
    still requires macOS, an explicitly Debug-iphonesimulator source and a fresh
    artifacts/signed directory. No signing identities are enumerated.
    """
    if sys.platform != "darwin":
        raise RuntimeError("CI Simulator copy signing requires macOS")
    run = run or subprocess.run
    artifacts = Path(artifacts).resolve(strict=True)
    if not artifacts.is_dir():
        raise ValueError("Artifact directory must already exist")
    source, bundle, executable = _source_app(app_path, bundle)
    evidence = artifacts / "signed"
    if evidence.exists() or evidence.is_symlink():
        raise ValueError("CI signed-copy directory must be fresh")
    if evidence.is_relative_to(source) or source.is_relative_to(evidence):
        raise ValueError("CI signed copy and source must be disjoint")
    for entry in source.rglob("*"):
        if entry.is_symlink():
            raise ValueError("Source app tree must not contain symlinks")
    if not _is_macho(source / executable):
        raise ValueError("Source app executable is not Mach-O")
    evidence.mkdir()
    copy = evidence / source.name
    record = {"schema": 1, "result": "running", "source_unchanged": True, "app": str(copy),
              "team": TEAM_ID, "identity": "ad-hoc", "signed_targets": []}
    _save_json(evidence / "signing.json", record)
    try:
        shutil.copytree(source, copy, symlinks=False)
        entitlements = {"application-identifier": TEAM_ID + "." + bundle,
                        "keychain-access-groups": [TEAM_ID + "." + bundle],
                        "com.apple.developer.team-identifier": TEAM_ID,
                        "get-task-allow": True}
        entitlement_path = evidence / "CI-simulator-entitlements.plist"
        with entitlement_path.open("wb") as stream:
            plistlib.dump(entitlements, stream, sort_keys=True)
        targets = []
        for entry in copy.rglob("*"):
            if entry.is_dir() and entry.suffix == ".framework":
                framework_executable = entry.stem
                info_file = entry / "Info.plist"
                if info_file.is_file():
                    with info_file.open("rb") as stream:
                        framework_info = plistlib.load(stream)
                    framework_executable = framework_info.get("CFBundleExecutable", framework_executable)
                if (type(framework_executable) is not str or not EXECUTABLE_RE.fullmatch(framework_executable)
                        or framework_executable in (".", "..")):
                    raise ValueError("Invalid copied framework executable")
                binary = entry / framework_executable
                if not binary.is_file() or not _is_macho(binary):
                    raise ValueError("Copied framework executable is not Mach-O")
                targets.append(entry)
            elif entry.is_file() and entry.suffix == ".dylib" and _is_macho(entry):
                targets.append(entry)
        targets.sort(key=lambda path: (-len(path.parts), str(path)))
        for index, target in enumerate(targets):
            label = f"codesign-{index:03d}-nested"
            _codesign(["codesign", "--force", "--sign", "-", "--timestamp=none", str(target)], evidence, label, run)
            record["signed_targets"].append(str(target.relative_to(copy)))
            _save_json(evidence / "signing.json", record)
        _codesign(["codesign", "--force", "--sign", "-", "--timestamp=none", "--entitlements",
                   str(entitlement_path), "--generate-entitlement-der", str(copy)], evidence, "codesign-app", run)
        record["signed_targets"].append(".")
        _codesign(["codesign", "--verify", "--strict", "--verbose=2", str(copy)], evidence, "codesign-verify", run)
        # Display ONLY the derived CI copy, never all app/source/account entitlements.
        _codesign(["codesign", "--display", "--entitlements", ":-", str(copy)], evidence, "codesign-ci-entitlements", run)
        record["result"] = "signed-copy-created"
        _save_json(evidence / "signing.json", record)
        return copy
    except BaseException as error:
        record["result"] = "failed"
        record["error"] = _error(error)
        _save_json(evidence / "signing.json", record)
        raise


def _container(runner, udid, bundle, label):
    # CI6: this read-only lookup timed out before A could even launch. Retry only timeouts,
    # never malformed paths or an actual nonzero command/launch result (2 x 15s maximum).
    for attempt in range(2):
        command_label = label + "-container" + ("-retry" if attempt else "")
        try:
            output = runner.call(["get_app_container", udid, bundle, "data"], command_label, 15)
            break
        except subprocess.TimeoutExpired:
            if attempt:
                raise
    if not isinstance(output, str) or len(output.splitlines()) != 1 or any(ord(character) < 32 for character in output.strip()):
        raise ReportError("simctl did not return one absolute app-data container")
    path = Path(output.strip())
    _report_path(path)
    return path


def _wait_report(container, phase, record, report_file, save, sleep, clock):
    deadline = clock() + REPORT_TIMEOUT
    # The iteration cap also bounds injected/non-advancing test clocks.
    for _ in range(int(REPORT_TIMEOUT / POLL_INTERVAL) + 1):
        report = read_native_report(container, phase)
        if report is not None:
            record["report"] = report
            _save_json(report_file, report)
            save()
            if report["probeComplete"] and report["graphComplete"]:
                return report
        remaining = deadline - clock()
        if remaining <= 0:
            break
        sleep(min(POLL_INTERVAL, remaining))
    raise TimeoutError("Native startup report missing or incomplete after a bounded 30-second wait")


def _checks_ok(report):
    return (report["probeComplete"] is True and report["graphComplete"] is True
            and report["probeOk"] is True and report["graphOk"] is True and report["sessionAbsent"] is True)


def _screenshot(artifacts, runner, udid, filename, label):
    from ios_simulator_smoke import png_dimensions
    path = artifacts / filename
    runner.call(["io", udid, "screenshot", str(path)], label, COMMAND_TIMEOUT)
    png_dimensions(path)
    return filename


def _capture_failure(artifacts, runner, udid, label, record, container, phase, save, bundle):
    if container is not None:
        try:
            report = read_native_report(container, phase)
            if report is not None:
                record["report"] = report
                _save_json(artifacts / (label + "-startup-report.json"), report)
        except Exception as error:
            record["failure_report_error"] = _error(error)
    try:
        record["failure_screenshot"] = _screenshot(artifacts, runner, udid, label + "-failure.png",
                                                       label + "-failure-screenshot")
    except Exception as error:
        record["failure_screenshot_error"] = _error(error)
    try:
        # Only this owned Simulator and fixed launch/security processes; never host/global logs.
        # Keep the bundle filter even for daemon logs; the application process is app-private.
        predicate = ('process == "BetterZslf" OR ((process == "SpringBoard" OR '
                     'process == "runningboardd" OR process == "amfid" OR process == "installd" OR '
                     'process == "launchd_sim") AND (eventMessage CONTAINS[c] "' + bundle +
                     '" OR eventMessage CONTAINS[c] "BetterZslf"))')
        runner.call(["spawn", udid, "log", "show", "--style", "compact", "--last", "3m",
                     "--predicate", predicate], label + "-launch-system-log", 15)
        record["failure_launch_log"] = label + "-launch-system-log.stdout.log"
    except Exception as error:
        record["failure_launch_log_error"] = _error(error)
    save()


def _terminate_and_confirm(runner, udid, bundle, pid, label):
    terminate_error = None
    try:
        runner.call(["terminate", udid, bundle], label + "-terminate", COMMAND_TIMEOUT)
    except Exception as error:
        terminate_error = _error(error)
    output = runner.call(["spawn", udid, "launchctl", "list"], label + "-after-terminate", COMMAND_TIMEOUT)
    lines = [line.split() for line in output.splitlines() if line.strip()]
    if not lines or lines[0] != ["PID", "Status", "Label"]:
        raise RuntimeError("Cannot confirm terminated PID from launchctl evidence")
    if pid is not None and any(len(columns) == 3 and columns[0] == str(pid) for columns in lines[1:]):
        raise RuntimeError("Previous probe PID is still alive after terminate")
    # A crashed app can make terminate return nonzero. Confirmed absence, not the
    # terminate exit alone, is the evidence that permits the next launch.
    return {"pid": pid, "confirmed_absent": True, "terminate_error": terminate_error}


def run_diagnostics(artifacts, app_path, bundle, udid, runner, update_pid, sleep=time.sleep,
                    *, signer=None, clock=time.monotonic):
    """Run A unsigned(seed), then B signed(seed -> terminated PID -> verify).

    runner.call(args, label, timeout) is the smoke schema. Its call_env takes
    (args, label, env: dict[str, str], timeout=60) and applies only those supplied
    SIMCTL_CHILD_* entries to the launched child. `update_pid(int)` must persist
    every successfully parsed launch PID to the smoke owner's record.

    Optional signer(artifacts: Path, source: Path, bundle: str) -> Path is an
    offline seam; production uses sign_simulator_copy. clock/sleep may be fake.
    Returns a JSON-compatible dict including the final live `pid`; never creates,
    boots, selects, shuts down, erases, deletes or acts on another simulator.
    """
    from ios_simulator_smoke import canonical_udid, parse_launch_pid
    artifacts = Path(artifacts).resolve(strict=True)
    if not artifacts.is_dir():
        raise ValueError("Artifact directory must already exist")
    udid = canonical_udid(udid)
    if type(bundle) is not str or not BUNDLE_RE.fullmatch(bundle):
        raise ValueError("Invalid diagnostic bundle identity")
    source, _, _ = _source_app(app_path, bundle)
    if not callable(update_pid) or not callable(getattr(runner, "call_env", None)):
        raise ValueError("Diagnostics require a PID ownership callback and runner.call_env")
    signer = signer or sign_simulator_copy
    result = {"schema": 1, "result": "running", "udid": udid, "bundle": bundle, "pid": None,
              "namespaces": {"unsigned": uuid.uuid4().hex + "_unsigned", "signed": uuid.uuid4().hex + "_signed"},
              "unsigned": {}, "signed": {"seed": {}, "verify": {}},
              "coverage": "Synthetic keychain probe plus actual AppGraphGuard; no account login or credentials"}
    summary = artifacts / "keychain-diagnostics.json"

    def save():
        _save_json(summary, result)

    def launch_phase(kind, phase, require_checks):
        label = kind if kind == "unsigned" else kind + "-" + phase
        record = result["unsigned"] if kind == "unsigned" else result["signed"][phase]
        record.update({"phase": phase, "result": "running", "report": None,
                       "report_file": label + "-startup-report.json"})
        container = None
        save()
        try:
            container = _container(runner, udid, bundle, label)
            record["stale_report_removed"] = _clear_report(container)
            env = {"SIMCTL_CHILD_BETTER_ZSLF_STARTUP_PROBE": phase,
                   "SIMCTL_CHILD_BETTER_ZSLF_PROBE_NAMESPACE": result["namespaces"][kind]}
            output = runner.call_env(["launch", udid, bundle], label + "-launch", env, timeout=30)
            pid = parse_launch_pid(output, bundle)
            record["pid"] = pid
            result["pid"] = pid
            update_pid(pid)
            save()
            report = _wait_report(container, phase, record, artifacts / record["report_file"], save, sleep, clock)
            record["checks_ok"] = _checks_ok(report)
            if require_checks and not record["checks_ok"]:
                raise RuntimeError("Signed native probe/AppGraphGuard/session-absence checks failed")
            record["result"] = "passed" if record["checks_ok"] else "observed-failure"
            save()
            return pid
        except BaseException as error:
            record["result"] = "failed"
            record["error"] = _error(error)
            _capture_failure(artifacts, runner, udid, label, record, container, phase, save, bundle)
            raise

    save()
    unsigned_pid = None
    unsigned_errors = []
    try:
        runner.call(["install", udid, str(source)], "unsigned-install", 90)
        unsigned_pid = launch_phase("unsigned", "seed", False)
    except Exception as error:
        unsigned_errors.append(_error(error))
        if not result["unsigned"]:
            result["unsigned"].update({"phase": "seed", "result": "failed", "report": None, "error": _error(error)})
            _capture_failure(artifacts, runner, udid, "unsigned", result["unsigned"], None, "seed", save, bundle)
    try:
        result["unsigned"]["screenshot"] = _screenshot(artifacts, runner, udid, "unsigned-startup.png", "unsigned-screenshot")
    except Exception as error:
        unsigned_errors.append(_error(error))
        result["unsigned"]["screenshot_error"] = _error(error)
    try:
        result["unsigned"]["termination"] = _terminate_and_confirm(
            runner, udid, bundle, result["unsigned"].get("pid", unsigned_pid), "unsigned")
    except Exception as error:
        unsigned_errors.append(_error(error))
        result["unsigned"]["termination_error"] = _error(error)
    result["unsigned"]["evidence_errors"] = unsigned_errors
    save()  # Baseline failure is retained and never short-circuits B.

    active_phase = "sign"
    try:
        signed_app = Path(signer(artifacts, source, bundle)).resolve(strict=True)
        signed_root = (artifacts / "signed").resolve(strict=True)
        if signed_app == source or not signed_app.is_dir() or not signed_app.is_relative_to(signed_root):
            raise ValueError("Signer must return only an artifacts/signed app copy")
        _source_app_metadata = signed_app / "Info.plist"
        with _source_app_metadata.open("rb") as stream:
            signed_info = plistlib.load(stream)
        if signed_info.get("CFBundleIdentifier") != bundle:
            raise ValueError("Signed copy changed the bundle identity")
        result["signed"]["app"] = str(signed_app.relative_to(artifacts))
        save()
        active_phase = "seed"
        runner.call(["install", udid, str(signed_app)], "signed-install", 90)
        seed_pid = launch_phase("signed", "seed", True)
        result["signed"]["seed"]["screenshot"] = _screenshot(
            artifacts, runner, udid, "signed-seed.png", "signed-seed-screenshot")
        result["signed"]["seed"]["termination"] = _terminate_and_confirm(runner, udid, bundle, seed_pid, "signed-seed")
        save()
        active_phase = "verify"
        verify_pid = launch_phase("signed", "verify", True)
        if verify_pid == seed_pid:
            raise RuntimeError("Verify launch reused the seed PID; cross-process evidence is ambiguous")
        result["signed"]["result"] = "passed"
        if unsigned_errors:
            raise RuntimeError("Unsigned baseline evidence is missing/invalid; signed checks alone do not pass A/B")
        result["result"] = "keychain-diagnostics-passed"
        result["pid"] = verify_pid
        save()
        return result
    except BaseException as error:
        result["result"] = "keychain-diagnostics-failed"
        result["error"] = _error(error)
        if result["signed"].get("result") != "passed":
            result["signed"]["result"] = "failed"
            record = result["signed"].setdefault(active_phase, {})
            record["result"] = "failed"
            record.setdefault("error", _error(error))
            # launch_phase already captured its failure. Also cover signing,
            # install, seed screenshot, terminate and cross-PID checks here.
            if "failure_screenshot" not in record and "failure_screenshot_error" not in record:
                container = None
                if active_phase in ("seed", "verify"):
                    try:
                        container = _container(runner, udid, bundle, "signed-" + active_phase + "-failure")
                    except Exception as capture_error:
                        record["failure_container_error"] = _error(capture_error)
                _capture_failure(artifacts, runner, udid, "signed-" + active_phase, record, container,
                                 active_phase if active_phase in ("seed", "verify") else "seed", save, bundle)
        save()
        raise


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("sign",))
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--bundle")
    args = parser.parse_args(argv)
    copy = sign_simulator_copy(args.artifacts, args.app, args.bundle)
    print(copy)


if __name__ == "__main__":
    main()
