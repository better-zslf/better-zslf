#!/usr/bin/env bash
# Only a copied Debug-iphonesimulator product receives the synthetic CI identity.
set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
  printf '%s\n' 'CI Simulator copy signing requires macOS' >&2
  exit 1
fi

script_dir="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "${script_dir}/ios_keychain_diagnostics.py" sign "$@"
