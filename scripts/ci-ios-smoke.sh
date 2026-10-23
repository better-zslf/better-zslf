#!/usr/bin/env bash
# CI Keychain A/B plus startup evidence; Login screenshot still needs review. No account or permission input.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
[[ "$(uname -s)" == Darwin ]] || { echo 'Native simulator smoke requires macOS.' >&2; exit 1; }
edition="${ORG_GRADLE_PROJECT_betterZslfEdition-pro}"
app=""
for argument in "$@"; do
  case "$argument" in
    -PbetterZslfEdition=*) edition="${argument#-PbetterZslfEdition=}" ;;
    -*) echo 'Usage: ci-ios-smoke.sh [-PbetterZslfEdition=standard|pro] [built-simulator.app]' >&2; exit 2 ;;
    *)
      [[ -z "$app" && -n "$argument" ]] || { echo 'Expected at most one built simulator app.' >&2; exit 2; }
      app="$argument"
      ;;
  esac
done
case "$edition" in
  standard) bundle=io.github.betterzslf ;;
  pro) bundle=io.github.betterzslf.pro ;;
  *) echo 'betterZslfEdition must be standard or pro.' >&2; exit 2 ;;
esac
export ORG_GRADLE_PROJECT_betterZslfEdition="$edition"
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
runtime_args=()
if [[ -n "${BETTER_ZSLF_CI_RUNTIME:-}" ]]; then
  runtime_args=(--runtime "$BETTER_ZSLF_CI_RUNTIME")
fi
# Guarded array expansion also preserves an empty default under macOS Bash 3.2 nounset.
if [[ -n "$app" ]]; then
  python3 scripts/ios_simulator_smoke.py smoke ${runtime_args[@]+"${runtime_args[@]}"} --bundle "$bundle" --keychain-diagnostics --artifacts "$artifacts" --app "$app"
else
  python3 scripts/ios_simulator_smoke.py smoke ${runtime_args[@]+"${runtime_args[@]}"} --bundle "$bundle" --keychain-diagnostics --artifacts "$artifacts"
fi
