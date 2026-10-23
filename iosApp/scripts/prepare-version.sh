#!/usr/bin/env bash
# Thin bridge: the single persisted version source stays the repository-root version.properties.
# All validation (numeric Apple marketing version, optional rcN prerelease, edition suffix,
# bounded Gradle-compatible overrides) lives in the offline Python helper so CI and local runs agree.
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$script_dir/version_settings.py" prepare "$@"
