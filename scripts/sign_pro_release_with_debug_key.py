#!/usr/bin/env python3
"""Sign the Pro release APK with the local Android debug key and verify the result.

The user asked for a non-debug (release, minified) Pro build signed with the debug key, so it
can be installed next to nothing else and inspected. `apksigner` is the only checker that
proves the APK scheme actually verifies, so it is run on the signed output, not just trusted.
"""
from __future__ import annotations

import hashlib
import json
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(r"E:\better-zslf")
UNSIGNED = ROOT / "work" / "pro-ci" / "androidApp" / "build" / "outputs" / "apk" / "release" / "androidApp-release-unsigned.apk"
DIST = ROOT / ".local" / "rc60-dist"
DEBUG_KEYSTORE = pathlib.Path.home() / ".android" / "debug.keystore"
# The debug keystore's published defaults; changing them would break adb install parity.
DEBUG_STORE_PASS = "android"
DEBUG_ALIAS = "androiddebugkey"
DEBUG_KEY_PASS = "android"
OUT_NAME = "betterzslf-pro-1.0.0-rc60-pro-release-debugsigned.apk"


def build_tools() -> pathlib.Path:
    candidates = sorted((ROOT / ".local" / "android-sdk" / "build-tools").iterdir())
    if not candidates:
        raise SystemExit("No build-tools installed")
    return candidates[-1]


def run(cmd: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(cmd, capture_output=True, text=True, check=False)


def main() -> int:
    if not UNSIGNED.is_file():
        raise SystemExit(f"Missing unsigned release APK: {UNSIGNED}")
    if not DEBUG_KEYSTORE.is_file():
        raise SystemExit(f"Missing debug keystore: {DEBUG_KEYSTORE}")
    bt = build_tools()
    apksigner = bt / "apksigner.bat"
    zipalign = bt / "zipalign.exe"
    aapt = bt / ("aapt2.exe" if (bt / "aapt2.exe").exists() else "aapt.exe")
    for tool in (apksigner, zipalign, aapt):
        if not tool.exists():
            raise SystemExit(f"Missing build tool: {tool}")

    DIST.mkdir(parents=True, exist_ok=True)
    aligned = DIST / "aligned-unsigned.apk"
    # apksigner requires the archive to be zipaligned before signing, and a release R8 build is
    # not guaranteed to be, so align into a fresh copy instead of mutating the build output.
    align = run([str(zipalign), "-p", "-f", "4", str(UNSIGNED), str(aligned)])
    if align.returncode:
        raise SystemExit(f"zipalign failed: {align.stderr or align.stdout}")

    signed = DIST / OUT_NAME
    if signed.exists():
        signed.unlink()
    sign = run([
        str(apksigner), "sign",
        "--ks", str(DEBUG_KEYSTORE),
        "--ks-key-alias", DEBUG_ALIAS,
        "--ks-pass", f"pass:{DEBUG_STORE_PASS}",
        "--key-pass", f"pass:{DEBUG_KEY_PASS}",
        "--v1-signing-enabled", "true",
        "--v2-signing-enabled", "true",
        "--v3-signing-enabled", "true",
        "--out", str(signed),
        str(aligned),
    ])
    if sign.returncode:
        raise SystemExit(f"apksigner sign failed: {sign.stderr or sign.stdout}")

    verify = run(["cmd", "/c", str(apksigner), "verify", "--print-certs", str(signed)])
    certs = [line.strip() for line in (verify.stdout + verify.stderr).splitlines()
             if "SHA-256 digest" in line or "Signer #1 certificate DN" in line]
    if verify.returncode:
        raise SystemExit(f"apksigner verify failed: {verify.stderr or verify.stdout}")

    badging = run([str(aapt), "dump", "badging", str(signed)])
    pkg = [l for l in badging.stdout.splitlines() if l.startswith("package:")][:1]
    label = [l for l in badging.stdout.splitlines() if l.startswith("application-label:")][:1]
    sdk = [l for l in badging.stdout.splitlines() if l.startswith("sdkVersion") or l.startswith("targetSdkVersion")]

    digest = hashlib.sha256(signed.read_bytes()).hexdigest()
    (DIST / f"{OUT_NAME}.sha256").write_text(f"{digest}  {OUT_NAME}\n", encoding="utf-8")

    report = {
        "unsigned_source": str(UNSIGNED),
        "output": str(signed),
        "bytes": signed.stat().st_size,
        "sha256": digest,
        "apksigner_verify_rc": verify.returncode,
        "certificate": certs,
        "package": pkg,
        "label": label,
        "sdk": sdk,
        "minified": True,
        "debuggable_run": "the signed build is a release build; only the signing key is the debug key",
    }
    (DIST / "release-report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
