#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
python3 scripts/verify-wrapper.py
python3 scripts/display_text_guard.py --root .
bash ./gradlew --no-daemon --stacktrace \
  :shared:jvmTest \
  :androidApp:testDebugUnitTest \
  :androidApp:lintDebug \
  :androidApp:assembleDebug
