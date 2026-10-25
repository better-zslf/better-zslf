#!/usr/bin/env python3
"""Owned-Simulator unsigned/ad-hoc A/B evidence; no account or certificate access.

Only Debug-iphonesimulator products are copied; the historical method signs its copy,
while the explicit Xcode method audits a packaged copy without re-signing. Native report fields
are an allowlist, not a source of paths, commands, credentials, or inferred status
codes. A successful signed seed/verify does not turn missing unsigned evidence
into a pass. The smoke owner remains responsible for device ownership/cleanup
and the final PID stability window/startup.png.
"""
import argparse
import hashlib
import struct
from datetime import datetime, timezone
import json
import math
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


def _codesign(command, evidence, label, run, *, timeout=60):
    """Persist command/stdout/stderr/result without changing signing commands/profile."""
    from ios_simulator_smoke import begin_command_result, finish_command_result, is_smoke_deadline
    _save_json(evidence / (label + ".command.json"), command)
    record = begin_command_result(label, timeout)
    _save_json(evidence / (label + ".result.json"), record)
    stdout, stderr = b"", b""
    try:
        process = run(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                      timeout=timeout, check=False)
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


SIGNING_METHODS = ("manual-ad-hoc", "xcode-simulator")
RESTRICTED_IDENTITIES = {"application-identifier", "com.apple.application-identifier",
                         "com.apple.developer.team-identifier", "keychain-access-groups"}
MAX_LAYOUT_BYTES = 1024 * 1024
MAX_SIGNATURE_BYTES = 4 * 1024 * 1024


def validate_signing_method(method, mode):
    if method not in SIGNING_METHODS:
        raise ValueError("Unknown Simulator signing method")
    if method == "xcode-simulator" and mode != "signed-positive":
        raise ValueError("xcode-simulator requires independent signed-positive; historical AB stays manual")


def file_sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_entitlements(value):
    """Type-exact comparison: JSON true is not 1, including inside dicts/lists."""
    nodes = [0]
    def check(item, depth):
        nodes[0] += 1
        if depth > 16 or nodes[0] > 1024:
            raise ValueError("Entitlement comparison exceeded bounded coverage")
        if type(item) is dict:
            if any(type(key) is not str for key in item):
                raise ValueError("Entitlement dictionary keys must be strings")
            for child in item.values():
                check(child, depth + 1)
        elif type(item) is list:
            for child in item:
                check(child, depth + 1)
        elif type(item) not in (str, bool, int):
            raise ValueError("Unsupported entitlement comparison type")
    if type(value) is not dict:
        raise ValueError("Entitlement comparison root must be a dictionary")
    check(value, 0)
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True, allow_nan=False)


def decode_entitlement_der(raw):
    """Bounded Apple DER plist subset; unknown forms fail before installation."""
    if not raw or len(raw) > MAX_REPORT_BYTES:
        raise ValueError("Entitlement DER size is invalid")
    nodes = [0]
    def node(pos, limit, depth):
        nodes[0] += 1
        if depth > 16 or nodes[0] > 1024 or pos + 2 > limit:
            raise ValueError("Entitlement DER depth/length is invalid")
        tag, size = raw[pos], raw[pos + 1]
        pos += 2
        if size & 128:
            width = size & 127
            if not 0 < width <= 4 or pos + width > limit:
                raise ValueError("Entitlement DER length is unsupported")
            size = int.from_bytes(raw[pos:pos + width], "big")
            pos += width
        end = pos + size
        if end > limit:
            raise ValueError("Entitlement DER is truncated")
        if tag & 32:
            items = []
            while pos < end:
                value, pos = node(pos, end, depth + 1)
                items.append(value)
            if tag == 0x70:
                if len(items) != 2 or type(items[0]) is not int or items[0] != 1 or type(items[1]) is not dict:
                    raise ValueError("Unsupported entitlement DER version")
                return items[1], end
            if tag == 0xb0 or (tag == 0x31 and depth == 0):
                result = {}
                for pair in items:
                    if type(pair) is not list or len(pair) != 2 or type(pair[0]) is not str or pair[0] in result:
                        raise ValueError("Entitlement DER dictionary is invalid")
                    result[pair[0]] = pair[1]
                return result, end
            if tag not in (0x30, 0x31):
                raise ValueError("Unsupported entitlement DER container")
            return items, end
        body = raw[pos:end]
        if tag == 0x0c:
            return body.decode("utf-8"), end
        if tag == 1 and body in (b"\x00", b"\xff"):
            return body == b"\xff", end
        if tag == 2 and 0 < len(body) <= 8:
            return int.from_bytes(body, "big", signed=True), end
        raise ValueError("Unsupported entitlement DER value")
    value, end = node(0, len(raw), 0)
    if type(value) is not dict or any(raw[end:]):
        raise ValueError("Entitlement DER root/trailing bytes are invalid")
    return value


def inspect_macho(path, evidence, label):
    """Stream headers and seek bounded sections/signature; never read an entire dylib."""
    path = Path(path)
    record = {"file": path.name, "bytes": path.stat().st_size, "sha256": file_sha256(path),
              "sections": [], "simulated_xml": None, "simulated_der": None,
              "signature_xml": {}, "signature_der": {}, "code_directory": None}
    def xml(raw):
        value = plistlib.loads(raw.rstrip(b"\0"))
        if type(value) is not dict:
            raise ValueError("Entitlement XML must be a dictionary")
        canonical_entitlements(value)  # Reject unsupported XML types before storing JSON evidence.
        return value
    try:
        with path.open("rb") as stream:
            def read_at(offset, length, maximum):
                if not 0 <= length <= maximum or offset < 0 or offset + length > record["bytes"]:
                    raise ValueError("Mach-O range exceeds bounded file coverage")
                stream.seek(offset)
                result = stream.read(length)
                if len(result) != length:
                    raise ValueError("Truncated Mach-O data")
                return result
            header = read_at(0, 32, 32)
            if header[:4] != b"\xcf\xfa\xed\xfe":
                raise ValueError("Packaging audit supports thin little-endian Mach-O64 only")
            count, size = struct.unpack_from("<II", header, 16)
            if not 0 < count <= 1024:
                raise ValueError("Mach-O command count is unsupported")
            commands = read_at(32, size, MAX_LAYOUT_BYTES)
            pos, signature = 0, None
            for _ in range(count):
                if pos + 8 > size:
                    raise ValueError("Truncated Mach-O command")
                cmd, length = struct.unpack_from("<II", commands, pos)
                if length < 8 or pos + length > size:
                    raise ValueError("Invalid Mach-O command size")
                if cmd == 0x19:
                    if length < 72:
                        raise ValueError("Truncated Mach-O segment")
                    nsects = struct.unpack_from("<I", commands, pos + 64)[0]
                    if nsects > 512 or 72 + nsects * 80 > length:
                        raise ValueError("Invalid Mach-O sections")
                    for index in range(nsects):
                        at = pos + 72 + index * 80
                        name = commands[at:at + 16].split(b"\0", 1)[0].decode("ascii")
                        segment = commands[at + 16:at + 32].split(b"\0", 1)[0].decode("ascii")
                        extent, offset = struct.unpack_from("<QI", commands, at + 40)
                        record["sections"].append({"segment": segment, "section": name,
                                                   "offset": offset, "size": extent})
                        if segment == "__TEXT" and name in ("__entitlements", "__ents_der"):
                            key = "simulated_xml" if name == "__entitlements" else "simulated_der"
                            if record[key] is not None:
                                raise ValueError("Duplicate simulated entitlement section")
                            raw = read_at(offset, extent, MAX_REPORT_BYTES)
                            (evidence / (label + "." + name + ".bin")).write_bytes(raw)
                            record[key] = xml(raw) if key.endswith("xml") else decode_entitlement_der(raw)
                elif cmd == 0x1d:
                    if signature is not None or length < 16:
                        raise ValueError("Invalid duplicate/truncated code signature command")
                    signature = struct.unpack_from("<II", commands, pos + 8)
                pos += length
            if pos != size or signature is None:
                raise ValueError("Unknown command layout or missing code signature")
            sig = read_at(*signature, MAX_SIGNATURE_BYTES)
            if len(sig) < 12:
                raise ValueError("Missing signature SuperBlob")
            magic, total, count = struct.unpack_from(">III", sig, 0)
            if magic != 0xfade0cc0 or total > len(sig) or count > 64 or 12 + count * 8 > total:
                raise ValueError("Unsupported signature SuperBlob")
            slots = set()
            record["signature_slots"] = []
            for index in range(count):
                slot, offset = struct.unpack_from(">II", sig, 12 + index * 8)
                if slot in slots or offset < 12 + count * 8 or offset + 8 > total:
                    raise ValueError("Invalid signature slot")
                slots.add(slot)
                magic, length = struct.unpack_from(">II", sig, offset)
                if length < 8 or offset + length > total:
                    raise ValueError("Truncated signature blob")
                blob, body = sig[offset:offset + length], sig[offset + 8:offset + length]
                record["signature_slots"].append({"slot": slot, "magic": hex(magic), "size": length})
                if slot == 0:
                    if magic != 0xfade0c02 or len(blob) < 52:
                        raise ValueError("Unsupported CodeDirectory")
                    version, flags = struct.unpack_from(">II", blob, 8)
                    def cstring(at):
                        if at < 44 or at >= len(blob) or b"\0" not in blob[at:]:
                            raise ValueError("Invalid CodeDirectory string")
                        return blob[at:].split(b"\0", 1)[0].decode("utf-8")
                    ident = struct.unpack_from(">I", blob, 20)[0]
                    team = struct.unpack_from(">I", blob, 48)[0] if version >= 0x20200 else 0
                    record["code_directory"] = {"version": hex(version), "flags": flags,
                                                "identifier": cstring(ident),
                                                "team": cstring(team) if team else None}
                elif slot in (5, 7):
                    expected = 0xfade7171 if slot == 5 else 0xfade7172
                    if magic != expected or len(body) > MAX_REPORT_BYTES:
                        raise ValueError("Invalid entitlement signature slot")
                    key = "signature_xml" if slot == 5 else "signature_der"
                    (evidence / (label + "." + key + ".bin")).write_bytes(body)
                    record[key] = xml(body) if slot == 5 else decode_entitlement_der(body)
            if not record["code_directory"] or not record["code_directory"]["flags"] & 2:
                raise ValueError("Expected an actual ad-hoc CodeDirectory")
            if canonical_entitlements(record["signature_xml"]) != canonical_entitlements(record["signature_der"]):
                raise ValueError("Real signature XML/DER disagree")
            if RESTRICTED_IDENTITIES.intersection(record["signature_xml"]):
                raise ValueError("Real signature still carries restricted identity claims")
        return record
    except BaseException as error:
        record["error"] = _error(error)
        raise
    finally:
        _save_json(evidence / (label + ".json"), record)


def prepare_xcode_simulator_copy(artifacts, app_path, bundle=None, *, run=None):
    """Audit/copy only. No --sign call and no mutation of Xcode's packaged input."""
    if sys.platform != "darwin":
        raise RuntimeError("Xcode-packaged Simulator verification requires macOS")
    run = run or subprocess.run
    source, bundle, executable = _source_app(app_path, bundle)
    evidence = Path(artifacts).resolve(strict=True) / "signed"
    if evidence.exists() or source.is_relative_to(evidence) or evidence.is_relative_to(source):
        raise ValueError("Xcode input/copy/evidence must be fresh and disjoint")
    evidence.mkdir()
    copy = evidence / source.name
    record = {"schema": 1, "result": "running", "profile": "xcode-simulator-packaged",
              "signing_method": "xcode-simulator", "identity": None, "source_unchanged": None,
              "causality": "New multi-condition build method, not byte-identical or single-key attribution",
              "images": []}
    _save_json(evidence / "signing.json", record)
    try:
        entries = list(source.rglob("*"))
        if any(p.is_symlink() for p in entries):
            raise ValueError("Packaged source must not contain symlinks")
        paths = sorted(p for p in entries if p.is_file() and _is_macho(p))
        if not paths:
            raise ValueError("Packaged source has no Mach-O images")
        tool = shutil.which("codesign")
        if not tool:
            raise ValueError("codesign executable is absent from the current build/smoke PATH")
        record["codesign_executable"] = tool
        simulated = []
        for index, path in enumerate(paths):
            image = inspect_macho(path, evidence, f"source-{index:03d}")
            relative = str(path.relative_to(source))
            image["relative_file"] = relative
            record["images"].append(image)
            left, right = image["simulated_xml"], image["simulated_der"]
            if left is not None or right is not None:
                if left is None or right is None or canonical_entitlements(left) != canonical_entitlements(right):
                    raise ValueError("Simulated XML/DER sections are missing or disagree")
                identity = left.get("application-identifier", left.get("com.apple.application-identifier"))
                if type(identity) is not str or not identity.endswith("." + bundle) or identity == "." + bundle:
                    raise ValueError("Simulated application identity does not match the actual bundle")
                simulated.append((relative, left))
            _save_json(evidence / "signing.json", record)
        if not any(name in (executable, executable + ".debug.dylib") for name, _ in simulated):
            raise ValueError("No simulated application identity in main/debug image")
        if any(canonical_entitlements(value) != canonical_entitlements(simulated[0][1]) for _, value in simulated):
            raise ValueError("Simulated identity differs between app images")
        record["simulated_entitlements"] = simulated[0][1]
        record["identity"] = "ad-hoc"  # Observed CodeDirectory flags in every audited image.
        shutil.copytree(source, copy, symlinks=False)
        for index, path in enumerate(paths):
            target = copy / path.relative_to(source)
            copied = inspect_macho(target, evidence, f"copy-{index:03d}")
            if copied["sha256"] != record["images"][index]["sha256"]:
                raise ValueError("Packaged copy changed a Mach-O image")
            _codesign([tool, "--display", "--verbose=4", "-r-", "--entitlements", ":-", str(target)],
                      evidence, f"xcode-details-{index:03d}", run, timeout=15)
        _codesign([tool, "--verify", "--deep", "--strict", "--verbose=4", str(copy)],
                  evidence, "xcode-verify", run, timeout=15)
        if any(file_sha256(path) != record["images"][i]["sha256"] for i, path in enumerate(paths)):
            raise ValueError("Original packaged Mach-O changed during verification")
        record.update(result="xcode-packaged-copy-verified", source_unchanged=True, app=str(copy))
        _save_json(evidence / "signing.json", record)
        return copy
    except BaseException as error:
        record.update(result="failed", error=_error(error))
        _save_json(evidence / "signing.json", record)
        raise


def _build_capture(command, evidence, label, timeout, run):
    """Stream build output to files, not an in-memory PIPE. Only called on CI macOS."""
    from ios_simulator_smoke import begin_command_result, finish_command_result, is_smoke_deadline
    record = begin_command_result(label, timeout)
    _save_json(evidence / (label + ".command.json"), command)
    _save_json(evidence / (label + ".result.json"), record)
    out, err = evidence / (label + ".stdout.log"), evidence / (label + ".stderr.log")
    try:
        with out.open("wb") as stdout, err.open("wb") as stderr:
            result = run(command, stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr, timeout=timeout, check=False)
        record.update(returncode=result.returncode, drain_complete=True)
        if result.returncode:
            record["outcome"] = "nonzero"
            raise RuntimeError("Xcode packaging command failed; see " + label + " logs")
        record.update(outcome="success", ok=True)
    except BaseException as error:
        record["error_type"] = type(error).__name__
        record["timed_out"] = isinstance(error, subprocess.TimeoutExpired) or is_smoke_deadline(error)
        if record["outcome"] == "running":
            record["outcome"] = "timeout" if record["timed_out"] else "error"
        raise
    finally:
        finish_command_result(record, b"", b"")
        record.update(stdout_bytes=out.stat().st_size if out.exists() else 0,
                      stderr_bytes=err.stat().st_size if err.exists() else 0)
        _save_json(evidence / (label + ".result.json"), record)


def build_xcode_simulator(derived_data, *, run=None):
    if sys.platform != "darwin":
        raise RuntimeError("Xcode packaging build requires macOS")
    run = run or subprocess.run
    derived = Path(derived_data).resolve()
    evidence = derived / "Logs" / "xcode-packaging"
    evidence.mkdir(parents=True, exist_ok=False)
    entitlements = evidence / "CI-Simulator.entitlements"
    with entitlements.open("wb") as stream:
        plistlib.dump({"application-identifier": TEAM_ID + ".io.github.betterzslf",
                      "com.apple.developer.team-identifier": TEAM_ID, "get-task-allow": True}, stream)
    command = ["xcodebuild", "-project", "iosApp/BetterZslf.xcodeproj", "-scheme", "BetterZslf",
               "-configuration", "Debug", "-sdk", "iphonesimulator", "-destination", "generic/platform=iOS Simulator",
               "-derivedDataPath", str(derived), "-resultBundlePath", str(derived / "build.xcresult"),
               "CODE_SIGNING_ALLOWED=YES", "CODE_SIGN_IDENTITY=-", "CODE_SIGN_ENTITLEMENTS=" + str(entitlements),
               "OTHER_CODE_SIGN_FLAGS="]
    _build_capture(["xcodebuild", "-version"], evidence, "xcode-version", 30, run)
    _build_capture(["xcrun", "--sdk", "iphonesimulator", "--show-sdk-version"], evidence, "sdk-version", 30, run)
    _build_capture(["xcrun", "--find", "codesign"], evidence, "codesign-path", 15, run)
    # Metadata queries must not create/consume the real build's result bundle.
    settings_command = command.copy()
    result_index = settings_command.index("-resultBundlePath")
    del settings_command[result_index:result_index + 2]
    _build_capture(settings_command + ["-showBuildSettings"], evidence, "buildsettings", 60, run)
    primary_error = None
    try:
        _build_capture(command + ["build"], evidence, "build", 1200, run)
    except BaseException as error:
        primary_error = error
        raise
    finally:
        # Retention is secondary evidence, never a replacement for a failed build/watchdog.
        try:
            source = derived / "Build/Intermediates.noindex/BetterZslf.build/Debug-iphonesimulator/BetterZslf.build"
            retained = evidence / "packaging-inputs"
            retained.mkdir()
            index = []
            _save_json(evidence / "packaging-inputs.json", index)
            for path in sorted(source.glob("*")):
                if path.is_file() and not path.is_symlink() and ("xcent" in path.name or path.suffix == ".der"):
                    if len(index) >= 64 or path.stat().st_size > MAX_LAYOUT_BYTES:
                        raise ValueError("Packaging input retention exceeded bounded coverage")
                    shutil.copyfile(path, retained / path.name)
                    index.append({"file": path.name, "sha256": file_sha256(path), "bytes": path.stat().st_size})
                    _save_json(evidence / "packaging-inputs.json", index)
        except BaseException as error:
            try:
                _save_json(evidence / "packaging-retention-error.json", _error(error))
            except BaseException:
                if primary_error is None:
                    raise
            if primary_error is None:
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


def parse_host_ps_candidates(output, udid, executable, app_name):
    """Strict fixed ps(comm), never argv; only an exact owned Simulator app path is a candidate."""
    if (type(output) is not str or not output.endswith("\n") or not output.strip()
            or len(output.encode("utf-8")) > 256 * 1024 or "\0" in output
            or type(executable) is not str or not EXECUTABLE_RE.fullmatch(executable)
            or type(app_name) is not str or "/" in app_name or not app_name.endswith(".app")):
        raise ValueError("Host ps snapshot is incomplete or has invalid identity")
    seen, candidates = set(), []
    target = re.compile(r"^/(?:[^/\r\n]+/)*Library/Developer/CoreSimulator/Devices/" + re.escape(udid)
                        + r"/data/Containers/Bundle/Application/[A-Fa-f0-9]{8}-(?:[A-Fa-f0-9]{4}-){3}[A-Fa-f0-9]{12}/"
                        + re.escape(app_name) + "/" + re.escape(executable) + r"$")
    for line in output.splitlines():
        fields = line.split(None, 2)
        if (len(fields) != 3 or not re.fullmatch(r"[0-9]+", fields[0])
                or not re.fullmatch(r"[0-9]+", fields[1]) or int(fields[0]) in seen
                or any(ord(char) < 32 for char in fields[2])):
            raise ValueError("Host ps snapshot has a malformed or duplicate row")
        pid, ppid, comm = int(fields[0]), int(fields[1]), fields[2]
        seen.add(pid)
        if pid > 0 and target.fullmatch(comm) and not any(part in (".", "..") for part in comm.split("/")):
            candidates.append({"pid": pid, "ppid": ppid, "comm": comm})
    return {"valid_full": True, "row_count": len(seen), "observed_candidates": candidates,
            "interpretation": "Path-scoped observation only; never a verified launch PID or PASS"}


def _observe_failed_launch(artifacts, runner, udid, label, record, container, phase, save, bundle,
                           executable, app_name, clock):
    """One early report + three readonly calls share 15s and the existing body deadline."""
    from ios_simulator_smoke import parse_launchctl_table, is_smoke_deadline
    started = clock()
    body_deadline = getattr(runner, "body_deadline_monotonic", None)
    if body_deadline is not None and (type(body_deadline) not in (int, float) or not math.isfinite(body_deadline)):
        raise ValueError("Invalid existing body deadline; observation skipped")
    deadline = min(started + 15, body_deadline) if body_deadline is not None else started + 15
    evidence = {"schema": 1, "budget_seconds": 15, "started_at_utc": _utc_now(),
                "started_monotonic": started, "deadline_monotonic": deadline,
                "body_deadline_monotonic": body_deadline, "steps": [], "result": "observation_only"}
    record["failure_observations"] = evidence

    def step(kind, operation, *, command=False):
        entry = {"kind": kind, "source_time_utc": _utc_now(), "started_monotonic": clock(),
                 "outcome": "unknown"}
        evidence["steps"].append(entry)
        remaining = deadline - clock()
        if remaining <= (0.5 if command else 0):
            entry["outcome"] = "skipped_budget"
            save()
            return
        timeout = min(3.0, remaining - 0.5) if command else None
        if command:
            entry["budget_seconds"] = timeout
        try:
            value = operation(timeout)
            if clock() > deadline:
                entry["outcome"] = "late_unknown"
            else:
                entry.update(value)
        except BaseException as error:
            entry.update(outcome="unknown", error=_error(error))
            if is_smoke_deadline(error) or not isinstance(error, Exception):
                raise
        finally:
            entry["finished_at_utc"] = _utc_now()
            entry["finished_monotonic"] = clock()
            save()

    def native(_):
        if container is None:
            return {"outcome": "unknown", "reason": "No previously known data container"}
        value = read_native_report(container, phase)
        if value is None:
            return {"outcome": "unknown", "reason": "No report at the fixed owned path"}
        if clock() <= deadline:
            record["report"] = value
            _save_json(artifacts / (label + "-startup-report.json"), value)
        return {"outcome": "observed_report", "report_file": label + "-startup-report.json",
                "interpretation": "Native observation does not complete the failed launch contract"}

    def processes(timeout):
        query = label + "-failure-processes"
        rows = parse_launchctl_table(runner.call(["spawn", udid, "launchctl", "list"], query, timeout))
        pattern = re.compile(r"(?:UIKitApplication:|application\.)" + re.escape(bundle) + r"(?:\[|\.|$)")
        return {"outcome": "observed", "valid_full": True, "stdout_file": query + ".stdout.log",
                "observed_bundle_pids": [int(row[0]) for row in rows if row[0] != "-" and pattern.match(row[2])],
                "interpretation": "Not assigned to owner/verified PID and not an absence gate"}

    def host(kind, timeout):
        query = label + "-failure-host-" + kind
        output = runner.call_host(kind, query, timeout)
        value = parse_host_ps_candidates(output, udid, executable, app_name) if kind == "ps" else {}
        outcome = ("captured" if output.strip() else "unknown") if kind == "vm_stat" else (
            "observed" if value.get("observed_candidates") else "unknown")
        return {"outcome": outcome, "stdout_file": query + ".stdout.log",
                "stderr_file": query + ".stderr.log", **value}

    try:
        step("early_native_report", native)
        step("launchctl", processes, command=True)
        step("host_ps_comm", lambda timeout: host("ps", timeout), command=True)
        step("host_vm_stat", lambda timeout: host("vm_stat", timeout), command=True)
    finally:
        evidence["finished_at_utc"] = _utc_now()
        evidence["elapsed_seconds"] = clock() - started
        evidence["budget_exhausted"] = clock() >= deadline
        evidence["body_deadline_exhausted"] = type(body_deadline) in (int, float) and clock() >= body_deadline
        save()


def _capture_failure(artifacts, runner, udid, label, record, container, phase, save, bundle,
                     *, primary=None, executable=None, app_name=None, clock=time.monotonic):
    from ios_simulator_smoke import is_smoke_deadline
    record["failure_capture_started_at_utc"] = _utc_now()

    def persist():
        try:
            save()
        except BaseException as error:
            record.setdefault("failure_evidence_errors", []).append(_error(error))
            if primary is None or is_smoke_deadline(error) or not isinstance(error, Exception):
                raise

    try:
        if record.get("launch_command_failed") is True:
            _observe_failed_launch(artifacts, runner, udid, label, record, container, phase, persist,
                                   bundle, executable, app_name, clock)
            if record["failure_observations"].get("body_deadline_exhausted"):
                if primary is not None:
                    primary.smoke_deadline_expired = True
                return
        record["failure_observed_at_utc"] = _utc_now()
        record["failure_launch_log"] = label + "-launch-system-log.stdout.log"
        record["failure_launch_log_window_seconds"] = 30
        record["failure_launch_log_complete"] = False
        record["failure_launch_log_started_at_utc"] = _utc_now()
        persist()
        try:
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
                raise
        finally:
            record["failure_launch_log_finished_at_utc"] = _utc_now()
            record["failure_launch_log_partial"] = not record["failure_launch_log_complete"]
            persist()
        if container is not None and "failure_observations" not in record:
            try:
                report = read_native_report(container, phase)
                if report is not None:
                    record["report"] = report
                    _save_json(artifacts / (label + "-startup-report.json"), report)
            except Exception as error:
                record["failure_report_error"] = _error(error)
                if is_smoke_deadline(error):
                    raise
        try:
            record["failure_screenshot"] = _screenshot(artifacts, runner, udid, label + "-failure.png",
                                                      label + "-failure-screenshot",
                                                      attempts=record.setdefault("failure_screenshot_attempts", []), save=persist)
        except Exception as error:
            record["failure_screenshot_error"] = _error(error)
            if is_smoke_deadline(error):
                raise
    except BaseException as error:
        if primary is None:
            raise
        record["failure_capture_error"] = _error(error)
        if is_smoke_deadline(error):
            primary.smoke_deadline_expired = True
        elif not isinstance(error, Exception):
            primary.failure_capture_interrupted = True
    finally:
        record["failure_capture_finished_at_utc"] = _utc_now()
        try:
            persist()
        except BaseException as error:
            if primary is None:
                raise
            if is_smoke_deadline(error):
                primary.smoke_deadline_expired = True
            elif not isinstance(error, Exception):
                primary.failure_capture_interrupted = True


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
                    *, signer=None, clock=time.monotonic, mode="ab", signing_method="manual-ad-hoc"):
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
    source, _, executable = _source_app(app_path, bundle)
    if not callable(update_pid) or not callable(getattr(runner, "call_env", None)):
        raise ValueError("Diagnostics require a PID ownership callback and runner.call_env")
    if mode not in ("ab", "signed-positive"):
        raise ValueError("Expected ab or signed-positive diagnostic mode")
    validate_signing_method(signing_method, mode)
    signer = signer or (prepare_xcode_simulator_copy if signing_method == "xcode-simulator" else sign_simulator_copy)
    result = {"schema": 1, "result": "running", "experiment_mode": mode, "signing_method": signing_method,
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

    def save_failure(primary, record):
        try:
            save()
        except BaseException as secondary:
            record.setdefault("failure_evidence_errors", []).append(_error(secondary))
            from ios_simulator_smoke import is_smoke_deadline
            if is_smoke_deadline(secondary):
                primary.smoke_deadline_expired = True
            elif not isinstance(secondary, Exception):
                primary.failure_capture_interrupted = True

    def abort_ab_deadline(error):
        from ios_simulator_smoke import is_smoke_deadline
        if not is_smoke_deadline(error) and not getattr(error, "failure_capture_interrupted", False):
            return False
        result.update(result="keychain-diagnostics-failed", error=_error(error))
        result["unsigned"]["evidence_errors"] = unsigned_errors
        result["signed"]["result"] = "blocked"
        result["between_phases"]["result"] = "blocked"
        save_failure(error, result["unsigned"])
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
            launch_error = None
            try:
                output = runner.call_env(["launch", udid, bundle], label + "-launch", env, timeout=30)
            except BaseException as error:
                launch_error = error
                record["launch_command_failed"] = True
                raise
            finally:
                record["launch_finished_at_utc"] = _utc_now()
                if launch_error is None:
                    save()
                else:
                    save_failure(launch_error, record)
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
            if (is_smoke_deadline(error) or not isinstance(error, Exception)
                    or getattr(error, "failure_capture_interrupted", False)):
                result.update(result="keychain-diagnostics-failed", error=_error(error))
                save_failure(error, record)
                raise
            _capture_failure(artifacts, runner, udid, label, record, container, phase, save, bundle,
                             primary=error, executable=executable, app_name=source.name, clock=clock)
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
                _capture_failure(artifacts, runner, udid, "unsigned", result["unsigned"], None, "seed", save, bundle,
                                 primary=error, executable=executable, app_name=source.name, clock=clock)
                if abort_ab_deadline(error):
                    raise
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
        if (is_smoke_deadline(error) or not isinstance(error, Exception)
                or getattr(error, "failure_capture_interrupted", False)):
            result["signed"]["result"] = "failed"
            save_failure(error, result["signed"])
            raise
        if result["signed"].get("result") not in ("passed", "blocked"):
            result["signed"]["result"] = "failed"
            record = result["signed"].setdefault(active_phase, {})
            record["result"] = "failed"
            record.setdefault("error", _error(error))
            # launch_phase already captured its failure. Also cover signing,
            # install, seed screenshot, terminate and cross-PID checks here.
            if "failure_capture_started_at_utc" not in record:
                # No repeated capture/container query, including when secondary evidence IO failed.
                _capture_failure(artifacts, runner, udid, "signed-" + active_phase, record, None,
                                 active_phase if active_phase in ("seed", "verify") else "seed", save, bundle,
                                 primary=error, executable=executable, app_name=source.name, clock=clock)
        save_failure(error, result["signed"])
        raise


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("sign", "build-xcode"))
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--app", type=Path)
    parser.add_argument("--bundle")
    args = parser.parse_args(argv)
    if args.mode == "build-xcode":
        build_xcode_simulator(args.artifacts)
    else:
        if args.app is None:
            parser.error("sign requires --app")
        copy = sign_simulator_copy(args.artifacts, args.app, args.bundle)
        print(copy)


if __name__ == "__main__":
    main()
