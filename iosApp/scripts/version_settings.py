#!/usr/bin/env python3
"""Offline iOS version bridge. Never invoke Xcode, Gradle, signing, or network tools."""
from __future__ import annotations

from dataclasses import dataclass, replace
import os
from pathlib import Path
import re
import sys
import tempfile
from typing import Mapping, Sequence

ROOT = Path(__file__).resolve().parents[2]
BASE_VERSION = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\Z")
PRERELEASE = re.compile(r"rc[1-9][0-9]{0,5}\Z")
BUNDLES = {"standard": "io.github.betterzslf", "pro": "io.github.betterzslf.pro"}


@dataclass(frozen=True)
class VersionSettings:
    marketing_version: str
    version_code: int
    prerelease: str | None
    edition: str

    @property
    def display_version(self) -> str:
        return self.marketing_version + ("-" + self.prerelease if self.prerelease else "") + ("-pro" if self.edition == "pro" else "")

    @property
    def bundle(self) -> str:
        return BUNDLES[self.edition]


def validated_settings(base: str, code: str, prerelease: str | None, edition: str) -> VersionSettings:
    if edition not in BUNDLES:
        raise ValueError("betterZslfEdition must be standard or pro.")
    if not BASE_VERSION.fullmatch(base):
        raise ValueError("Apple marketing version must be a numeric major.minor.patch without leading zeroes.")
    if not re.fullmatch(r"[1-9][0-9]*", code) or len(code) > 10 or int(code) > 2_100_000_000:
        raise ValueError("versionCode must be an integer in 1..2100000000.")
    if prerelease is not None and not PRERELEASE.fullmatch(prerelease):
        raise ValueError("VERSION_PRERELEASE, when present, must match rc[1-9][0-9]{0,5}.")
    return VersionSettings(base, int(code), prerelease, edition)


def load_properties(path: Path, edition: str = "pro") -> VersionSettings:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        key = key.strip()
        if not separator or key in values:
            raise ValueError("Malformed or duplicate version property.")
        values[key] = value.strip()
    required = {"VERSION_NAME", "VERSION_CODE"}
    if not required <= values.keys() or not values.keys() <= required | {"VERSION_PRERELEASE"}:
        raise ValueError("Expected VERSION_NAME, VERSION_CODE and optional VERSION_PRERELEASE only.")
    return validated_settings(values["VERSION_NAME"], values["VERSION_CODE"], values.get("VERSION_PRERELEASE"), edition)


def prepare_settings(properties: Path, arguments: Sequence[str] = (), environ: Mapping[str, str] | None = None) -> VersionSettings:
    env = os.environ if environ is None else environ
    overrides = {key: env["ORG_GRADLE_PROJECT_" + key] for key in ("versionName", "versionCode", "betterZslfEdition")
                 if "ORG_GRADLE_PROJECT_" + key in env}
    for argument in arguments:
        key, separator, value = argument.partition("=")
        if not separator or key not in ("-PversionName", "-PversionCode", "-PbetterZslfEdition"):
            raise ValueError("Expected -PversionName, -PversionCode or -PbetterZslfEdition overrides.")
        overrides[key[2:]] = value
    # Always validate the persisted source, including optional RC, before considering overrides.
    root = load_properties(properties, overrides.get("betterZslfEdition", "pro"))
    result = root
    if "versionName" in overrides:
        name = overrides["versionName"]
        if root.prerelease is not None:
            if name != root.display_version:
                raise ValueError("RC versionName override must exactly match the root full version and selected edition.")
        else:
            # Legacy two-field stable sources accept their original numeric overrides.
            # A complete stable Pro name from metadata is also valid; RC is never invented here.
            if root.edition == "pro" and name.endswith("-pro"):
                name = name[:-4]
            result = validated_settings(name, str(root.version_code), None, root.edition)
    if "versionCode" in overrides:
        result = validated_settings(result.marketing_version, overrides["versionCode"], result.prerelease, result.edition)
    return result


def xcode_settings(properties: Path, environ: Mapping[str, str]) -> VersionSettings:
    edition = environ.get("BETTER_ZSLF_EDITION", environ.get("ORG_GRADLE_PROJECT_betterZslfEdition", "pro"))
    root = load_properties(properties, edition)
    base = environ.get("MARKETING_VERSION", "")
    code = environ.get("CURRENT_PROJECT_VERSION", "")
    prerelease = environ.get("BETTER_ZSLF_VERSION_PRERELEASE") or None
    resolved = validated_settings(base, code, prerelease, edition)
    if root.prerelease is not None and resolved.display_version != root.display_version:
        raise ValueError("Resolved Xcode RC version must exactly match version.properties; regenerate the project.")
    if root.prerelease is None and prerelease is not None:
        raise ValueError("Xcode RC must be declared in version.properties.")
    for key, expected in (("PRODUCT_BUNDLE_IDENTIFIER", resolved.bundle), ("BETTER_ZSLF_BUNDLE_ID", resolved.bundle),
                          ("BETTER_ZSLF_VERSION_NAME", resolved.marketing_version),
                          ("BETTER_ZSLF_VERSION_CODE", str(resolved.version_code)),
                          ("BETTER_ZSLF_DISPLAY_VERSION", resolved.display_version)):
        if key in environ and environ[key] != expected:
            raise ValueError("Xcode version or edition bridge values disagree; regenerate the project.")
    if root.prerelease is not None and "BETTER_ZSLF_DISPLAY_VERSION" not in environ:
        raise ValueError("RC display version is missing from Xcode settings; regenerate the project.")
    return resolved


def xcconfig(settings: VersionSettings) -> str:
    return "\n".join((
        "// Generated from root version.properties plus validated Gradle-compatible overrides.",
        "// Do not edit or commit; rerun iosApp/scripts/generate-project.sh after version changes.",
        f"BETTER_ZSLF_VERSION_NAME = {settings.marketing_version}",
        f"BETTER_ZSLF_VERSION_CODE = {settings.version_code}",
        f"BETTER_ZSLF_VERSION_PRERELEASE = {settings.prerelease or ''}",
        f"BETTER_ZSLF_DISPLAY_VERSION = {settings.display_version}",
        f"BETTER_ZSLF_EDITION = {settings.edition}",
        f"BETTER_ZSLF_BUNDLE_ID = {settings.bundle}",
        "",
    ))


def write_config(output: Path, settings: VersionSettings) -> None:
    content = xcconfig(settings).encode("utf-8")
    if output.is_file() and output.read_bytes() == content:
        return
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=output.parent, prefix="Version.xcconfig.", delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(content)
    try:
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)


def main(arguments: Sequence[str] | None = None) -> int:
    args = list(sys.argv[1:] if arguments is None else arguments)
    try:
        mode = args.pop(0) if args else ""
        if mode in ("prepare", "validate"):
            settings = prepare_settings(ROOT / "version.properties", args)
            if mode == "prepare":
                write_config(ROOT / "iosApp/.generated/Version.xcconfig", settings)
            print("Prepared iOS version settings." if mode == "prepare" else "Validated iOS version settings.")
        elif mode == "xcode" and not args:
            settings = xcode_settings(ROOT / "version.properties", os.environ)
            # No newline: command substitution stays identical with Windows' bundled Python in offline tests.
            sys.stdout.write(f"{settings.display_version}\t{settings.version_code}\t{settings.edition}")
        else:
            raise ValueError("Expected prepare, validate or xcode version bridge mode.")
    except (OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
