#!/usr/bin/env python3
"""Seed only the workspace-local Wrapper cache from a verified official ZIP."""
import hashlib
from pathlib import Path
import shutil
import zipfile

from importlib.util import module_from_spec, spec_from_file_location

ROOT = Path(__file__).resolve().parents[1]
spec = spec_from_file_location("wrapper_check", ROOT / "scripts/verify-wrapper.py")
wrapper_check = module_from_spec(spec)
spec.loader.exec_module(wrapper_check)


def base36(value: int) -> str:
    digits = "0123456789abcdefghijklmnopqrstuvwxyz"
    result = ""
    while value:
        value, remainder = divmod(value, 36)
        result = digits[remainder] + result
    return result or "0"


def main() -> None:
    wrapper_check.main()
    version = wrapper_check.VERSION
    source = ROOT / f".local/downloads/gradle-{version}-bin.zip"
    if hashlib.sha256(source.read_bytes()).hexdigest() != wrapper_check.DIST_SHA256:
        raise SystemExit("Gradle cached source ZIP failed official SHA256 verification")
    url = f"https://services.gradle.org/distributions/gradle-{version}-bin.zip"
    # MD5/base36 is Gradle Wrapper's path-key format, not an integrity check.
    key = base36(int.from_bytes(hashlib.md5(url.encode()).digest(), "big"))
    cache = ROOT / f".local/gradle-user-home/wrapper/dists/gradle-{version}-bin" / key
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / source.name
    if not target.exists():
        shutil.copyfile(source, target)
    if hashlib.sha256(target.read_bytes()).hexdigest() != wrapper_check.DIST_SHA256:
        raise SystemExit("Existing Wrapper cache ZIP differs from verified distribution")
    extracted = cache / f"gradle-{version}"
    if not (extracted / "lib/gradle-launcher-9.6.1.jar").is_file():
        with zipfile.ZipFile(target) as archive:
            for member in archive.infolist():
                if not (cache / member.filename).resolve().is_relative_to(cache.resolve()):
                    raise SystemExit("Unexpected ZIP traversal entry")
            archive.extractall(cache)
    (cache / (source.name + ".ok")).touch()
    print(f"Verified Gradle {version} seeded in workspace-local Wrapper cache")


if __name__ == "__main__":
    main()
