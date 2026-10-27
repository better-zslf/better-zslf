#!/usr/bin/env python3
"""Single-source release version checks; never silently derive a new version."""
import argparse
import json
import os
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# Keep the Apple marketing triplet numeric; the optional RC suffix is display/tag metadata.
SEMVER = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\Z")
RELEASE_CANDIDATE = re.compile(r"rc[1-9][0-9]{0,5}\Z")


APPLICATION_IDS = {"standard": "io.github.betterzslf", "pro": "io.github.betterzslf.pro"}


def load_version(path: Path, tag: str | None = None, edition: str = "standard") -> dict:
    if edition not in APPLICATION_IDS:
        raise ValueError("Edition must be standard or pro")
    values = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        if not separator or key.strip() in values:
            raise ValueError("Malformed or duplicate version property")
        values[key.strip()] = value.strip()
    required = {"VERSION_NAME", "VERSION_CODE"}
    if not required <= set(values) <= required | {"VERSION_PRERELEASE"}:
        raise ValueError("version.properties requires VERSION_NAME and VERSION_CODE with only optional VERSION_PRERELEASE")
    name = values["VERSION_NAME"]
    code = values["VERSION_CODE"]
    if not SEMVER.fullmatch(name):
        raise ValueError("VERSION_NAME must use numeric x.y.z notation shared with iOS")
    if not re.fullmatch(r"[1-9][0-9]*", code) or int(code) > 2_100_000_000:
        raise ValueError("VERSION_CODE must be an integer in 1..2100000000")
    prerelease = values.get("VERSION_PRERELEASE")
    if prerelease is not None:
        if not RELEASE_CANDIDATE.fullmatch(prerelease):
            raise ValueError("VERSION_PRERELEASE must use rc followed by 1..6 digits without leading zero")
        name += "-" + prerelease
    if edition == "pro":
        name += "-pro"
    expected_tag = f"v{name}"
    if tag is not None and tag != expected_tag:
        raise ValueError("Tag must exactly match the selected edition and version.properties")
    return {"versionName": name, "versionCode": int(code), "tag": expected_tag}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--properties", type=Path, default=ROOT / "version.properties")
    parser.add_argument("--tag")
    parser.add_argument("--edition", choices=tuple(APPLICATION_IDS), default="standard")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--github-output", action="store_true")
    args = parser.parse_args()
    try:
        metadata = load_version(args.properties, args.tag, args.edition)
    except (OSError, ValueError) as error:
        raise SystemExit(str(error)) from None
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    if args.github_output:
        output = os.environ.get("GITHUB_OUTPUT")
        if not output:
            raise SystemExit("GITHUB_OUTPUT is missing")
        with open(output, "a", encoding="utf-8") as stream:
            stream.write(f"version_name={metadata['versionName']}\n")
            stream.write(f"version_code={metadata['versionCode']}\n")
            stream.write(f"tag={metadata['tag']}\n")
            stream.write(f"prerelease={str('-rc' in metadata['versionName']).lower()}\n")
    print(f"Release version verified: {metadata['tag']} ({metadata['versionCode']})")


if __name__ == "__main__":
    main()
