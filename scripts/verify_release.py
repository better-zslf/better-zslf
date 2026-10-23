#!/usr/bin/env python3
"""Verify signed Android output, version and signer before copying release assets."""
import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
from pathlib import Path

from release_metadata import APPLICATION_IDS, ROOT, load_version


def run(command: list[str]) -> str:
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode:
        # Avoid echoing subprocess arguments/output: aliases and paths may be private.
        raise ValueError(f"Release verification tool failed: {Path(command[0]).name}")
    return result.stdout


def only_file(pattern: str) -> Path:
    files = sorted(ROOT.glob(pattern))
    if len(files) != 1:
        raise ValueError(f"Expected exactly one release artifact matching {pattern}")
    return files[0]


def validate_apk_identity(badging: str, metadata: dict, edition: str) -> str:
    if edition not in APPLICATION_IDS:
        raise ValueError("Edition must be standard or pro")
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", badging, re.M)
    if not package or package[1] != APPLICATION_IDS[edition]:
        raise ValueError("APK applicationId differs from the selected edition")
    if package[2] != str(metadata["versionCode"]) or package[3] != metadata["versionName"]:
        raise ValueError("APK versionCode/versionName differs from the validated release version")
    return package[1]


def build_tool(tools: Path, name: str) -> str:
    candidates = (name + ".exe", name + ".bat", name) if os.name == "nt" else (name,)
    for candidate in candidates:
        if (tools / candidate).is_file():
            return str(tools / candidate)
    raise ValueError("Android build tool is missing: " + name)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--edition", choices=tuple(APPLICATION_IDS), default="standard")
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        metadata = json.loads(args.metadata.read_text(encoding="utf-8"))
        if metadata != load_version(ROOT / "version.properties", metadata.get("tag"), args.edition):
            raise ValueError("Release metadata changed after tag validation")
        expected_cert = os.environ.get("ANDROID_EXPECTED_CERT_SHA256", "").lower()
        if not re.fullmatch(r"[0-9a-f]{64}", expected_cert):
            raise ValueError("Validated signing certificate fingerprint is missing")
        sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if not sdk_root:
            raise ValueError("Android SDK path is missing")
        tools = Path(sdk_root) / "build-tools/36.0.0"
        apk = only_file("androidApp/build/outputs/apk/release/*.apk")
        aab = only_file("androidApp/build/outputs/bundle/release/*.aab")
        apk_signature = run([build_tool(tools, "apksigner"), "verify", "--verbose", "--print-certs", str(apk)])
        certs = re.findall(r"Signer #[0-9]+ certificate SHA-256 digest: ([a-fA-F0-9]+)", apk_signature)
        if certs != [expected_cert]:
            raise ValueError("APK signer differs from the configured release certificate")
        badging = run([build_tool(tools, "aapt"), "dump", "badging", str(apk)])
        application_id = validate_apk_identity(badging, metadata, args.edition)
        jarsigner = shutil.which("jarsigner")
        keytool = shutil.which("keytool")
        if not jarsigner or not keytool:
            raise ValueError("Full JDK signing tools are missing")
        bundle_verification = run([jarsigner, "-J-Duser.language=en", "-J-Duser.country=US", "-verify", str(aab)])
        if "jar verified." not in bundle_verification:
            raise ValueError("AAB is unsigned or its JAR signature is invalid")
        certificate_text = run([keytool, "-printcert", "-rfc", "-jarfile", str(aab)])
        pem = re.search(r"-----BEGIN CERTIFICATE-----\s*(.*?)\s*-----END CERTIFICATE-----", certificate_text, re.S)
        if not pem:
            raise ValueError("AAB signing certificate is missing")
        bundle_certificate = base64.b64decode("".join(pem[1].split()), validate=True)
        if hashlib.sha256(bundle_certificate).hexdigest() != expected_cert:
            raise ValueError("APK and AAB signing certificates differ")
        source_commit = run(["git", "-C", str(ROOT), "rev-parse", "HEAD"]).strip()
        if not re.fullmatch(r"[0-9a-f]{40}", source_commit):
            raise ValueError("Source checkout commit is missing")
        declared_commit = os.environ.get("SOURCE_COMMIT_SHA")
        if declared_commit is not None and declared_commit != source_commit:
            raise ValueError("Source checkout differs from the validated upstream commit")
        if run(["git", "-C", str(ROOT), "status", "--porcelain", "--untracked-files=no"]).strip():
            raise ValueError("Release source checkout has uncommitted tracked changes")
        args.output.mkdir(parents=True, exist_ok=True)
        if any(args.output.iterdir()):
            raise ValueError("Release output directory must be empty")
        for original, suffix in ((apk, "apk"), (aab, "aab")):
            shutil.copyfile(original, args.output / f"better-zslf-{metadata['versionName']}.{suffix}")
        manifest = dict(metadata, edition=args.edition, applicationId=application_id, signingCertificateSha256=expected_cert,
                        gitCommit=source_commit)
        (args.output / "release.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        checksum_lines = [f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n"
                          for path in sorted(args.output.iterdir())]
        (args.output / "SHA256SUMS").write_text("".join(checksum_lines), encoding="utf-8")
        print("Release APK/AAB signatures, matching signer, APK version and artifact SHA256 verified")
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise SystemExit(str(error)) from None


if __name__ == "__main__":
    main()
