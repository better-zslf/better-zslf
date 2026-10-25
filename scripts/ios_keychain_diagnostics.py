#!/usr/bin/env python3
"""Owned-Simulator unsigned/ad-hoc A/B evidence; no account or certificate access.

Only the Debug-iphonesimulator product is copied and signed. Native report fields
are an allowlist, not a source of paths, commands, credentials, or inferred status
codes. A successful signed seed/verify does not turn missing unsigned evidence
into a pass. The smoke owner remains responsible for device ownership/cleanup
and the final PID stability window/startup.png.
"""
import argparse
from datetime import datetime, timezone
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
    record = {"type": type(error).__name__, "message": str(error)}
    if getattr(error, "smoke_deadline_expired", False) is True:
        record["smoke_deadline_expired"] = True
    return record


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
    """Persist command/stdout/stderr/result without changing signing commands/profile."""
    from ios_simulator_smoke import begin_command_result, finish_command_result, is_smoke_deadline
    _save_json(evidence / (label + ".command.json"), command)
    record = begin_command_result(label, 60)
    _save_json(evidence / (label + ".result.json"), record)
    stdout, stderr = b"", b""
    try:
        process = run(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                      timeout=60, check=False)
        stdout, stderr = _bytes(process.stdout), _bytes(process.stderr)
        record.update(returncode=process.returncode, drain_complete=True)
        if process.returncode:
            record["outcome"] = "nonzero"
            raise RuntimeError(f"CI simulator codesign failed ({process.returncode}); see {label} logs")
        record.update(outcome="success", ok=True)
    except BaseException as error:
        if record["outcome"] == "running":
            stdout = _bytes(getattr(error, "stdout", getattr(error, "output", None)))
            stderr = _bytes(getattr(error, "stderr", None))
            record["timed_out"] = isinstance(error, subprocess.TimeoutExpired) or is_smoke_deadline(error)
            record["outcome"] = "timeout" if record["timed_out"] else "error"
        record["error_type"] = type(error).__name__
        raise
    finally:
        (evidence / (label + ".stdout.log")).write_bytes(stdout)
        (evidence / (label + ".stderr.log")).write_bytes(stderr)
        finish_command_result(record, stdout, stderr)
        _save_json(evidence / (label + ".result.json"), record)


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
              "team": TEAM_ID, "identity": "ad-hoc", "profile": "simulator-single-app",
              "keychain_sharing": False, "signed_targets": []}
    _save_json(evidence / "signing.json", record)
    try:
        shutil.copytree(source, copy, symlinks=False)
        entitlements = {"application-identifier": TEAM_ID + "." + bundle,
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
        except subprocess.TimeoutExpired as error:
            from ios_simulator_smoke import is_smoke_deadline
            if attempt or is_smoke_deadline(error):
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


def _screenshot(artifacts, runner, udid, filename, label, *, attempts=None, save=None):
    from ios_simulator_smoke import png_dimensions
    attempts = attempts if attempts is not None else []
    original = Path(filename)
    for attempt in range(2):
        final_name = filename if not attempt else original.stem + "-attempt-2" + original.suffix
        command_label = label + ("-attempt-2" if attempt else "")
        path = artifacts / final_name
        entry = {"attempt": attempt + 1, "file": final_name, "label": command_label,
                 "result": "running", "started_at_utc": _utc_now()}
        attempts.append(entry)
        if save:
            save()
        try:
            runner.call(["io", udid, "screenshot", str(path)], command_label, 15)
            png_dimensions(path)
            entry["result"] = "passed"
            return final_name
        except subprocess.TimeoutExpired as error:
            entry.update(result="timeout", error=_error(error))
            from ios_simulator_smoke import is_smoke_deadline
            if attempt or is_smoke_deadline(error):
                raise
        except Exception as error:
            entry.update(result="failed", error=_error(error))
            raise  # Nonzero or bad PNG is not a retryable successful screenshot.
        finally:
            entry["finished_at_utc"] = _utc_now()
            if save:
                save()


def _utc_now():
    return datetime.now(timezone.utc).isoformat()


def _capture_failure(artifacts, runner, udid, label, record, container, phase, save, bundle):
    from ios_simulator_smoke import is_smoke_deadline
    # Collect the failing operation first: CI7's ascending 3m output timed out in old A logs.
    record["failure_observed_at_utc"] = _utc_now()
    record["failure_launch_log"] = label + "-launch-system-log.stdout.log"
    record["failure_launch_log_window_seconds"] = 30
    record["failure_launch_log_complete"] = False
    record["failure_launch_log_started_at_utc"] = _utc_now()
    save()
    try:
        # Only this owned Simulator and fixed launch/security processes; never host/global logs.
        predicate = ('process == "BetterZslf" OR ((process == "SpringBoard" OR '
                     'process == "runningboardd" OR process == "amfid" OR process == "installd" OR '
                     'process == "launchd_sim") AND (eventMessage CONTAINS[c] "' + bundle +
                     '" OR eventMessage CONTAINS[c] "BetterZslf"))')
        runner.call(["spawn", udid, "log", "show", "--style", "compact", "--last", "30s",
                     "--predicate", predicate], label + "-launch-system-log", 15)
        record["failure_launch_log_complete"] = True
    except Exception as error:
        record["failure_launch_log_error"] = _error(error)
        if is_smoke_deadline(error):
            raise  # Finally persists the original timeout/partial capture before exiting.
    finally:
        record["failure_launch_log_finished_at_utc"] = _utc_now()
        record["failure_launch_log_partial"] = not record["failure_launch_log_complete"]
        save()
    if container is not None:
        try:
            report = read_native_report(container, phase)
            if report is not None:
                record["report"] = report
                _save_json(artifacts / (label + "-startup-report.json"), report)
        except Exception as error:
            record["failure_report_error"] = _error(error)
            if is_smoke_deadline(error):
                save()
                raise
    try:
        record["failure_screenshot"] = _screenshot(artifacts, runner, udid, label + "-failure.png",
                                                  label + "-failure-screenshot",
                                                  attempts=record.setdefault("failure_screenshot_attempts", []), save=save)
    except Exception as error:
        record["failure_screenshot_error"] = _error(error)
        if is_smoke_deadline(error):
            save()
            raise
    save()


def _terminate_and_confirm(runner, udid, bundle, pid, label, *, record=None, save=None,
                           sleep=time.sleep, clock=time.monotonic):
    """One terminate; all readonly observations share a 15s deadline and 3-call cap."""
    from ios_simulator_smoke import parse_launchctl_table, is_smoke_deadline
    record = record if record is not None else {}
    record.update(pid=pid, confirmed_absent=False, terminate_error=None,
                  terminate_started_at_utc=_utc_now(), process_queries=[], process_query_attempts=0,
                  observation_budget_seconds=15, observation_max_queries=3)

    def persist():
        if save:
            save()

    persist()
    try:
        runner.call(["terminate", udid, bundle], label + "-terminate", COMMAND_TIMEOUT)
        record["terminate_outcome"] = "success"
    except BaseException as error:
        record.update(terminate_outcome="error", terminate_error=_error(error))
        if is_smoke_deadline(error) or not isinstance(error, Exception):
            raise  # The one-shot body watchdog/interrupt is never an operational tolerance.
    finally:
        record["terminate_finished_at_utc"] = _utc_now()
        persist()  # Retain this outcome even when a later valid table still has the PID.
    started = clock()
    deadline = started + 15
    record["observation_started_at_utc"] = _utc_now()
    record["observation_started_monotonic"] = started
    label_pattern = re.compile(r"(?:UIKitApplication:|application\.)" + re.escape(bundle) + r"(?:\[|\.|$)")
    retry_timeout = False
    original_present = False
    try:
        for attempt in range(3):
            remaining = deadline - clock()
            if remaining <= 0:
                raise TimeoutError("Process absence observation exceeded its 15-second deadline")
            suffix = "" if not attempt else ("-retry" if retry_timeout else f"-observe-{attempt + 1}")
            query_label = label + "-after-terminate" + suffix
            query = {"label": query_label, "stdout_file": query_label + ".stdout.log",
                     "stderr_file": query_label + ".stderr.log", "started_at_utc": _utc_now(),
                     "started_monotonic": clock(), "budget_seconds": min(15, remaining),
                     "outcome": "running", "valid_full": False, "absent": None}
            record["process_queries"].append(query)
            record["process_query_attempts"] = attempt + 1
            persist()
            try:
                output = runner.call(["spawn", udid, "launchctl", "list"], query_label, query["budget_seconds"])
                rows = parse_launchctl_table(output)
                original_present = pid is not None and any(columns[0] == str(pid) for columns in rows)
                bundle_pids = [int(columns[0]) for columns in rows
                               if columns[0] != "-" and label_pattern.match(columns[2])]
                absent = not original_present and not bundle_pids
                query.update(valid_full=True, original_pid_present=original_present,
                             bundle_live_pids=bundle_pids, absent=absent,
                             outcome="valid_absent" if absent else "valid_live")
                if clock() > deadline:
                    query["deadline_exceeded"] = True
                    raise TimeoutError("Process table arrived after the absence observation deadline")
                if absent:
                    record["confirmed_absent"] = True
                    return record
                retry_timeout = False
            except subprocess.TimeoutExpired as error:
                query.update(outcome="timeout", error=_error(error))
                # Keep the legacy single timeout retry only inside this SAME deadline.
                # A timeout while observing an already-live app stays unknown and blocks.
                if attempt or clock() >= deadline or is_smoke_deadline(error):
                    raise
                retry_timeout = True
            except Exception as error:
                query.update(outcome="deadline_exceeded" if query.get("deadline_exceeded") else "invalid_or_error",
                             error=_error(error))
                raise
            finally:
                query["finished_at_utc"] = _utc_now()
                query["finished_monotonic"] = clock()
                persist()
            if attempt < 2 and not retry_timeout:
                sleep(min(1.0, max(0, deadline - clock())))
        if original_present:
            raise RuntimeError("Previous probe PID is still alive after terminate")
        raise RuntimeError("Bundle process is still alive after terminate")
    finally:
        record["observation_finished_at_utc"] = _utc_now()
        record["observation_finished_monotonic"] = clock()
        persist()


def run_diagnostics(artifacts, app_path, bundle, udid, runner, update_pid, sleep=time.sleep,
                    *, signer=None, clock=time.monotonic, mode="ab"):
    """Default AB is preserved; signed-positive uses a fresh owned device with no A.

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
    if mode not in ("ab", "signed-positive"):
        raise ValueError("Expected ab or signed-positive diagnostic mode")
    signer = signer or sign_simulator_copy
    result = {"schema": 1, "result": "running", "experiment_mode": mode,
              "login_ui_verified": False, "ui_review": "pending", "udid": udid, "bundle": bundle, "pid": None,
              "namespaces": {"unsigned": uuid.uuid4().hex + "_unsigned", "signed": uuid.uuid4().hex + "_signed"},
              "unsigned": {}, "signed": {"seed": {}, "verify": {}},
              "between_phases": {"experiment": "owned-app-clean-reinstall", "result": "pending", "actions": [],
                                 "interpretation": "Clean reinstall plus simulator-single-app profile; not an established LS/sharing/signature root cause"},
              "coverage": "Synthetic keychain probe plus actual AppGraphGuard; no account login or credentials"}
    if mode == "signed-positive":
        result["unsigned"] = {"result": "not_run", "coverage": "historical-negative-only: CI7/CI9/CI10; not a current A pass"}
        result["namespaces"].pop("unsigned")
        result["between_phases"] = {"result": "not_applicable", "actions": [],
                                    "interpretation": "No A predecessor on this fresh owned device; original AB gates unchanged"}
        result["preparation"] = {"experiment": "fresh-owned-signed-positive", "result": "pending", "actions": [],
                                 "interpretation": "Independent positive test; not single-factor sharing/LS/resource root-cause attribution"}
        result["coverage"] += "; independent single-app signed seed/restart/verify; A not run; UI review pending"
    transition = result["between_phases"] if mode == "ab" else result["preparation"]
    summary = artifacts / "keychain-diagnostics.json"

    def save():
        _save_json(summary, result)

    def abort_ab_deadline(error):
        from ios_simulator_smoke import is_smoke_deadline
        if not is_smoke_deadline(error):
            return False
        result.update(result="keychain-diagnostics-failed", error=_error(error))
        result["unsigned"]["evidence_errors"] = unsigned_errors
        result["signed"]["result"] = "blocked"
        result["between_phases"]["result"] = "blocked"
        save()
        return True

    def transition_call(action, args, label, timeout):
        entry = {"action": action, "result": "running", "started_at_utc": _utc_now()}
        transition["actions"].append(entry)
        save()
        try:
            runner.call(args, label, timeout)
            entry["result"] = "passed"
        except BaseException as error:
            entry.update(result="failed", error=_error(error))
            transition["result"] = "failed"
            raise
        finally:
            entry["finished_at_utc"] = _utc_now()
            save()

    def launch_phase(kind, phase, require_checks):
        label = kind if kind == "unsigned" else kind + "-" + phase
        record = result["unsigned"] if kind == "unsigned" else result["signed"][phase]
        record.update({"phase": phase, "result": "running", "report": None,
                       "report_file": label + "-startup-report.json"})
        container = None
        save()
        try:
            container = _container(runner, udid, bundle, label)
            if kind == "signed" and phase == "seed":
                from ios_simulator_smoke import parse_launchctl_table
                query_label = label + "-before-launch"
                prelaunch = {"label": query_label, "started_at_utc": _utc_now(), "valid_full": False,
                             "stdout_file": query_label + ".stdout.log"}
                record["prelaunch_process_check"] = prelaunch
                save()
                try:
                    rows = parse_launchctl_table(runner.call(["spawn", udid, "launchctl", "list"], query_label, 15))
                    pattern = re.compile(r"(?:UIKitApplication:|application\.)" + re.escape(bundle) + r"(?:\[|\.|$)")
                    live = [int(row[0]) for row in rows if row[0] != "-" and pattern.match(row[2])]
                    previous = result["signed"]["seed"].get("pid") if phase == "verify" else None
                    previous_present = previous is not None and any(row[0] == str(previous) for row in rows)
                    prelaunch.update(valid_full=True, bundle_live_pids=live, previous_pid_present=previous_present)
                    if live or previous_present:
                        raise RuntimeError("Signed app or previous seed PID is already alive before launch")
                finally:
                    prelaunch["finished_at_utc"] = _utc_now()
                    save()
            record["stale_report_removed"] = _clear_report(container)
            env = {"SIMCTL_CHILD_BETTER_ZSLF_STARTUP_PROBE": phase,
                   "SIMCTL_CHILD_BETTER_ZSLF_PROBE_NAMESPACE": result["namespaces"][kind]}
            record["launch_started_at_utc"] = _utc_now()
            save()
            try:
                output = runner.call_env(["launch", udid, bundle], label + "-launch", env, timeout=30)
            finally:
                record["launch_finished_at_utc"] = _utc_now()
                save()
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
            from ios_simulator_smoke import is_smoke_deadline
            if is_smoke_deadline(error) or not isinstance(error, Exception):
                result.update(result="keychain-diagnostics-failed", error=_error(error))
                save()
                raise
            _capture_failure(artifacts, runner, udid, label, record, container, phase, save, bundle)
            raise

    save()
    unsigned_pid = None
    unsigned_errors = []
    exported = absent = False
    if mode == "ab":
        try:
            runner.call(["install", udid, str(source)], "unsigned-install", 90)
            unsigned_pid = launch_phase("unsigned", "seed", False)
        except Exception as error:
            unsigned_errors.append(_error(error))
            if abort_ab_deadline(error):
                raise
            if not result["unsigned"]:
                result["unsigned"].update({"phase": "seed", "result": "failed", "report": None, "error": _error(error)})
                _capture_failure(artifacts, runner, udid, "unsigned", result["unsigned"], None, "seed", save, bundle)
        try:
            result["unsigned"]["screenshot"] = _screenshot(artifacts, runner, udid, "unsigned-startup.png", "unsigned-screenshot",
                                                        attempts=result["unsigned"].setdefault("screenshot_attempts", []), save=save)
        except Exception as error:
            unsigned_errors.append(_error(error))
            result["unsigned"]["screenshot_error"] = _error(error)
            if abort_ab_deadline(error):
                raise
        try:
            termination = result["unsigned"].setdefault("termination", {})
            _terminate_and_confirm(runner, udid, bundle, result["unsigned"].get("pid", unsigned_pid), "unsigned",
                                   record=termination, save=save, sleep=sleep, clock=clock)
        except Exception as error:
            unsigned_errors.append(_error(error))
            result["unsigned"]["termination_error"] = _error(error)
            if abort_ab_deadline(error):
                raise
        result["unsigned"]["evidence_errors"] = unsigned_errors
        baseline = result["unsigned"]
        from ios_simulator_smoke import exported_png_is_valid
        exported = (baseline.get("report") is not None and
                    (artifacts / "unsigned-startup-report.json").is_file() and
                    exported_png_is_valid(artifacts, baseline.get("screenshot")))
        absent = baseline.get("termination", {}).get("confirmed_absent") is True
        result["between_phases"]["actions"].extend([
            {"action": "export-unsigned-evidence", "result": "passed" if exported else "failed",
             "screenshot": baseline.get("screenshot") if exported else None},
            {"action": "confirm-unsigned-absence", "result": "passed" if absent else "failed"},
        ])
        if absent:
            result["pid"] = None
        save()

    active_phase = "transition"
    try:
        if mode == "ab":
            if not exported or not absent or unsigned_errors:
                result["signed"]["result"] = "blocked"
                result["between_phases"]["result"] = "blocked"
                raise RuntimeError("Unsigned baseline evidence/termination absence is unconfirmed; signed experiment blocked")
            active_phase = "uninstall"
            transition_call("uninstall-unsigned-owned-app", ["uninstall", udid, bundle],
                            "between-uninstall-unsigned", 30)
        active_phase = "sign"
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
        transition_call("install-signed-copy", ["install", udid, str(signed_app)], "signed-install", 90)
        transition["result"] = "completed"
        save()
        seed_pid = launch_phase("signed", "seed", True)
        result["signed"]["seed"]["screenshot"] = _screenshot(
            artifacts, runner, udid, "signed-seed.png", "signed-seed-screenshot",
            attempts=result["signed"]["seed"].setdefault("screenshot_attempts", []), save=save)
        termination = result["signed"]["seed"].setdefault("termination", {})
        _terminate_and_confirm(runner, udid, bundle, seed_pid, "signed-seed",
                               record=termination, save=save, sleep=sleep, clock=clock)
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
        from ios_simulator_smoke import is_smoke_deadline
        if is_smoke_deadline(error) or not isinstance(error, Exception):
            result["signed"]["result"] = "failed"
            save()
            raise
        if result["signed"].get("result") not in ("passed", "blocked"):
            result["signed"]["result"] = "failed"
            record = result["signed"].setdefault(active_phase, {})
            record["result"] = "failed"
            record.setdefault("error", _error(error))
            # launch_phase already captured its failure. Also cover signing,
            # install, seed screenshot, terminate and cross-PID checks here.
            if "failure_screenshot" not in record and "failure_screenshot_error" not in record:
                # launch_phase already exported any native report. Do not put another
                # potentially slow container lookup ahead of the short failure log window.
                _capture_failure(artifacts, runner, udid, "signed-" + active_phase, record, None,
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
