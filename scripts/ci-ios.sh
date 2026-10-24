#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
[[ "$(uname -s)" == Darwin ]] || { echo 'iOS build requires macOS.' >&2; exit 1; }
: "${DEVELOPER_DIR:=/Applications/Xcode_26.4.1.app/Contents/Developer}"
export DEVELOPER_DIR
[[ -d "$DEVELOPER_DIR" ]] || { echo 'Pinned Xcode 26.4.1 is missing.' >&2; exit 1; }
xcodebuild -version
python3 scripts/verify-wrapper.py
bash iosApp/tests/version-scripts-test.sh
python3 iosApp/tests/check-capabilities.py
python3 iosApp/tests/check-map.py
bash ./gradlew --no-daemon --stacktrace :shared:linkDebugFrameworkIosSimulatorArm64
command -v xcodegen >/dev/null || { echo 'XcodeGen is missing.' >&2; exit 1; }
bash iosApp/scripts/generate-project.sh
mkdir -p .local/ios-ci
xcodebuild \
  -project iosApp/BetterZslf.xcodeproj \
  -scheme BetterZslf \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath .local/ios-ci \
  -resultBundlePath .local/ios-ci/build.xcresult \
  CODE_SIGNING_ALLOWED=NO \
  build
