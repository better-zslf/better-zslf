#!/usr/bin/env python3
"""Single-source release version checks; never silently derive a new version."""
import argparse
import json
import os
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# iOS MARKETING_VERSION and Android versionName share this numeric triplet.
SEMVER = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\Z")


def load_version(path: Path, tag: str | None = None) -> dict:
    values = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        if not separator or key.strip() in values:
            raise ValueError("Malformed or duplicate version property")
        values[key.strip()] = value.strip()
    if set(values) != {"VERSION_NAME", "VERSION_CODE"}:
        raise ValueError("version.properties must contain exactly VERSION_NAME and VERSION_CODE")
    name = values["VERSION_NAME"]
    code = values["VERSION_CODE"]
    if not SEMVER.fullmatch(name):
        raise ValueError("VERSION_NAME must use numeric x.y.z notation shared with iOS")
    if not re.fullmatch(r"[1-9][0-9]*", code) or int(code) > 2_100_000_000:
        raise ValueError("VERSION_CODE must be an integer in 1..2100000000")
    expected_tag = f"v{name}"
    if tag is not None and tag != expected_tag:
        raise ValueError("Tag must exactly match v + VERSION_NAME in version.properties")
    return {"versionName": name, "versionCode": int(code), "tag": expected_tag}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--properties", type=Path, default=ROOT / "version.properties")
    parser.add_argument("--tag")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--github-output", action="store_true")
    args = parser.parse_args()
    try:
        metadata = load_version(args.properties, args.tag)
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
    print(f"Release version verified: {metadata['tag']} ({metadata['versionCode']})")


if __name__ == "__main__":
    main()
