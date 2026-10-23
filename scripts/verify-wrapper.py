#!/usr/bin/env python3
"""Verify the official wrapper before invoking any Gradle project tasks."""
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION = "9.6.1"
# Published by Gradle at https://downloads.gradle.org/distributions/...
JAR_SHA256 = "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"
DIST_SHA256 = "9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14"
# Official v9.6.1 launchers; normalize only CRLF->LF for Git eol rules.
LAUNCHER_SHA256 = {
    "gradlew": "e01b5c97892572c82405c02b96a3382379100e7d825ce7c48883d95c26928750",
    "gradlew.bat": "4e193538b4ddccbee0c05d6383731b647945c146d16d8fe243b4ff716867896f",
}


def main() -> None:
    jar = ROOT / "gradle/wrapper/gradle-wrapper.jar"
    if not jar.is_file() or hashlib.sha256(jar.read_bytes()).hexdigest() != JAR_SHA256:
        raise SystemExit("Official Gradle wrapper JAR SHA256 check failed")
    properties = {}
    for raw in (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line and not line.startswith(("#", "!")):
            key, sep, value = line.partition("=")
            if not sep or key in properties:
                raise SystemExit("Malformed or duplicate Gradle wrapper property")
            properties[key] = value.replace("\\:", ":")
    expected_url = f"https://services.gradle.org/distributions/gradle-{VERSION}-bin.zip"
    if properties.get("distributionUrl") != expected_url:
        raise SystemExit("Gradle distribution URL is not the pinned official version")
    if properties.get("distributionSha256Sum") != DIST_SHA256:
        raise SystemExit("Gradle distribution SHA256 is missing or incorrect")
    if properties.get("validateDistributionUrl") != "true":
        raise SystemExit("Gradle distribution URL validation must remain enabled")
    for name, checksum in LAUNCHER_SHA256.items():
        script = ROOT / name
        if not script.is_file() or hashlib.sha256(script.read_bytes().replace(b"\r\n", b"\n")).hexdigest() != checksum:
            raise SystemExit(f"Official Gradle wrapper launcher SHA256 check failed: {name}")
    print(f"Official Gradle {VERSION} wrapper and distribution checksum verified")


if __name__ == "__main__":
    main()
