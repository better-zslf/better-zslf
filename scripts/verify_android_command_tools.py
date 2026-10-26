#!/usr/bin/env python3
"""Check host-specific setup-android v3 pins (stdlib only, no Gradle).

Offline default uses a small official repository excerpt. --live checks Google's
current XML before a pin update; unit tests intentionally make no HTTP requests.
The action constructs mac-{id}, not the newer mac_arm64/mac_x86_64 filenames.
"""
import argparse
import json
from pathlib import Path
import re
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_URL = "https://dl.google.com/android/repository/repository2-3.xml"
DOWNLOAD_BASE = "https://dl.google.com/android/repository/"
FIXTURE = ROOT / "scripts/tests/fixtures/android-commandline-tools.xml"
SETUP_ACTION = "android-actions/setup-android@9fc6c4e9069bf8d3d10b2204b1fb8f6ef7065407"


def child(element, name):
    return next((node for node in element if node.tag.rsplit("}", 1)[-1] == name), None)


def text(element, name):
    node = child(element, name)
    return node.text.strip() if node is not None and node.text else ""


def select_archive(repository, host, build_id):
    if host not in {"linux", "macosx"} or not re.fullmatch(r"[0-9]+", build_id):
        raise ValueError("Require a supported host and a numeric, immutable build ID")
    prefix = "mac" if host == "macosx" else "linux"
    filename = f"commandlinetools-{prefix}-{build_id}_latest.zip"
    matches = []
    for package in repository.iter():
        if package.tag.rsplit("}", 1)[-1] != "remotePackage":
            continue
        if not re.fullmatch(r"cmdline-tools;[0-9]+\.[0-9]+", package.get("path", "")):
            continue  # Exclude mutable ;latest, preview and duplicate alias packages.
        channel = child(package, "channelRef")
        archives = child(package, "archives")
        if channel is None or channel.get("ref") != "channel-0" or archives is None:
            continue
        for archive in archives:
            if text(archive, "host-os") != host:
                continue
            complete = child(archive, "complete")
            if complete is None or text(complete, "url") != filename:
                continue
            checksum = child(complete, "checksum")
            sha1 = checksum.text.strip() if checksum is not None and checksum.text else ""
            size = text(complete, "size")
            if (checksum is None or checksum.get("type") != "sha1" or
                    not re.fullmatch(r"[0-9a-f]{40}", sha1) or not size.isdecimal() or int(size) <= 0):
                raise ValueError("Official archive metadata has invalid size/checksum")
            matches.append(dict(package=package.get("path"), host=host,
                                url=DOWNLOAD_BASE + filename, size=int(size), sha1=sha1))
    if len(matches) != 1:
        raise ValueError(f"Expected one official {host} archive matching setup-android v3 URL: {filename}")
    return matches[0]


def workflow_pins(workflow):
    """Extract this workflow's two literal jobs; fail closed on matrix/alias changes.

    This is deliberately not a general YAML parser and needs no PyYAML on runners.
    Actionlint remains the workflow syntax validator.
    """
    result = {}
    for job, runner_prefix, host in (("android", "ubuntu-", "linux"), ("ios", "macos-", "macosx")):
        match = re.search(r"^  " + job + r":\r?\n(.*?)(?=^  [A-Za-z0-9_-]+:|\Z)", workflow, re.M | re.S)
        if not match:
            raise ValueError(f"Missing literal workflow job: {job}")
        block = match.group(1)
        runner = re.findall(r"^    runs-on:[ \t]*([A-Za-z0-9_.-]+)[ \t]*$", block, re.M)
        pins = re.findall(r"^[ \t]+cmdline-tools-version:[ \t]*(?:'([0-9]+)'|\"([0-9]+)\"|([0-9]+))[ \t]*(?:#.*)?$", block, re.M)
        if len(runner) != 1 or not runner[0].startswith(runner_prefix) or len(pins) != 1 or SETUP_ACTION not in block:
            raise ValueError(f"Unsupported runner/action/pin configuration for {job}; update this guard explicitly")
        result[job] = (host, next(value for value in pins[0] if value))
    return result


def validate_ci(workflow, repository):
    return {job: select_archive(repository, host, build)
            for job, (host, build) in workflow_pins(workflow).items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", action="store_true", help="Verify against Google's current XML")
    args = parser.parse_args()
    if args.live:
        with urllib.request.urlopen(REPOSITORY_URL, timeout=60) as response:
            payload = response.read(4_000_001)
        if len(payload) > 4_000_000:
            raise ValueError("Official repository XML exceeded the guard's size limit")
        repository = ET.fromstring(payload)
    else:
        repository = ET.parse(FIXTURE).getroot()
    from ci_workflow_contract import read_test_workflow
    workflow = read_test_workflow()
    print(json.dumps(validate_ci(workflow, repository), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
