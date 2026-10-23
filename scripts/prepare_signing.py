#!/usr/bin/env python3
"""Validate CI signing inputs without logging keystore content or credentials."""
import base64
import binascii
import hashlib
import os
import re
import shutil
import subprocess
from pathlib import Path

REQUIRED = (
    "ANDROID_KEYSTORE_BASE64",
    "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS",
    "ANDROID_KEY_PASSWORD",
)


def decode_keystore(environment: dict) -> bytes:
    missing = [name for name in REQUIRED if not environment.get(name)]
    if missing:
        raise ValueError("Missing required release signing secrets: " + ", ".join(missing))
    try:
        data = base64.b64decode("".join(environment[REQUIRED[0]].split()), validate=True)
    except (ValueError, binascii.Error):
        raise ValueError("ANDROID_KEYSTORE_BASE64 is not valid base64") from None
    if not data:
        raise ValueError("Decoded Android keystore is empty")
    return data


def main() -> None:
    destination = None
    try:
        data = decode_keystore(os.environ)
        runner_temp = os.environ.get("RUNNER_TEMP")
        github_env = os.environ.get("GITHUB_ENV")
        if not runner_temp or not github_env:
            raise ValueError("RUNNER_TEMP and GITHUB_ENV are required in CI")
        destination = Path(runner_temp).resolve() / "better-zslf-release.jks"
        if "\n" in str(destination) or "\r" in str(destination):
            raise ValueError("Invalid signing temporary path")
        destination.write_bytes(data)
        destination.chmod(0o600)
        keytool = shutil.which("keytool")
        if not keytool:
            raise ValueError("JDK keytool is missing")
        environment = dict(os.environ, ANDROID_KEYSTORE_PATH=str(destination))
        result = subprocess.run(
            [keytool, "-exportcert", "-keystore", str(destination),
             "-alias", environment["ANDROID_KEY_ALIAS"],
             "-storepass:env", "ANDROID_KEYSTORE_PASSWORD"],
            env=environment, capture_output=True, check=False,
        )
        if result.returncode or not result.stdout:
            raise ValueError("Keystore password or signing alias validation failed")
        fingerprint = hashlib.sha256(result.stdout).hexdigest()
        expected = os.environ.get("ANDROID_SIGNING_CERT_SHA256", "").replace(":", "").strip().lower()
        if expected and (not re.fullmatch(r"[0-9a-f]{64}", expected) or expected != fingerprint):
            raise ValueError("Signing certificate does not match ANDROID_SIGNING_CERT_SHA256")
        with open(github_env, "a", encoding="utf-8") as stream:
            stream.write(f"ANDROID_KEYSTORE_PATH={destination}\n")
            stream.write(f"ANDROID_EXPECTED_CERT_SHA256={fingerprint}\n")
        print("Release keystore decoded and signing alias validated; values are not logged")
    except (OSError, ValueError) as error:
        if destination is not None:
            destination.unlink(missing_ok=True)
        raise SystemExit(str(error)) from None


if __name__ == "__main__":
    main()
