#!/usr/bin/env bash
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ios_dir="$(CDPATH= cd -- "$script_dir/.." && pwd)"
/bin/bash "$script_dir/prepare-version.sh" "$@"
if ! command -v xcodegen >/dev/null 2>&1; then
  printf '%s\n' 'XcodeGen is required to generate the iOS project (on macOS: brew install xcodegen).' >&2
  exit 1
fi
exec xcodegen generate --spec "$ios_dir/project.yml" --project "$ios_dir"
