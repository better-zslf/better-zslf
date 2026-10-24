#!/usr/bin/env bash
# CI Keychain A/B plus startup evidence; Login screenshot still needs review. No account or permission input.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
[[ "$(uname -s)" == Darwin ]] || { echo 'Native simulator smoke requires macOS.' >&2; exit 1; }
[[ $# -le 1 ]] || { echo 'Usage: ci-ios-smoke.sh [built-simulator.app]' >&2; exit 2; }
python3 iosApp/tests/test-keychain-diagnostics.py
mkdir -p .local/ios-ci/Logs
artifacts="$(mktemp -d "$PWD/.local/ios-ci/Logs/simulator-smoke.XXXXXX")"
interrupted=0
cleanup() {
  local status=$?
  local cleanup_status
  trap - EXIT INT TERM
  set +e
  if [[ "$interrupted" == 1 ]]; then
    trap '' INT TERM # Finish the short owned shutdown/delete within the cancel grace.
    python3 scripts/ios_simulator_smoke.py cleanup --artifacts "$artifacts" --fast
  else
    python3 scripts/ios_simulator_smoke.py cleanup --artifacts "$artifacts"
  fi
  cleanup_status=$?
  if [[ "$status" == 0 && "$cleanup_status" != 0 ]]; then status=$cleanup_status; fi
  echo "Simulator smoke diagnostics: $artifacts"
  exit "$status"
}
trap cleanup EXIT
trap 'interrupted=1; exit 130' INT
trap 'interrupted=1; exit 143' TERM
if [[ $# == 1 ]]; then
  python3 scripts/ios_simulator_smoke.py smoke --keychain-diagnostics --artifacts "$artifacts" --app "$1"
else
  python3 scripts/ios_simulator_smoke.py smoke --keychain-diagnostics --artifacts "$artifacts"
fi
