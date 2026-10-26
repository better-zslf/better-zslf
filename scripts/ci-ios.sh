#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
[[ "$(uname -s)" == Darwin ]] || { echo 'iOS build requires macOS.' >&2; exit 1; }
method="${BETTER_ZSLF_CI_SIGNING_METHOD:-manual-ad-hoc}"
case "$method" in manual-ad-hoc|xcode-simulator) ;; *) echo 'Unknown Simulator signing method.' >&2; exit 2 ;; esac
edition="${ORG_GRADLE_PROJECT_betterZslfEdition-standard}"
for argument in "$@"; do
  case "$argument" in
    -PbetterZslfEdition=*) edition="${argument#-PbetterZslfEdition=}" ;;
    -PversionName=*|-PversionCode=*) ;;
    *) echo 'Expected Gradle version or betterZslfEdition overrides.' >&2; exit 2 ;;
  esac
done
case "$edition" in
  standard) bundle=io.github.betterzslf ;;
  pro) bundle=io.github.betterzslf.pro ;;
  *) echo 'betterZslfEdition must be standard or pro.' >&2; exit 2 ;;
esac
export ORG_GRADLE_PROJECT_betterZslfEdition="$edition"
: "${DEVELOPER_DIR:=/Applications/Xcode_26.4.1.app/Contents/Developer}"
export DEVELOPER_DIR
[[ -d "$DEVELOPER_DIR" ]] || { echo 'Pinned Xcode 26.4.1 is missing.' >&2; exit 1; }
xcodebuild -version
python3 scripts/verify-wrapper.py
bash iosApp/tests/version-scripts-test.sh
python3 iosApp/tests/check-capabilities.py
python3 iosApp/tests/check-map.py
bash ./gradlew --no-daemon --stacktrace "$@" :shared:linkDebugFrameworkIosSimulatorArm64
command -v xcodegen >/dev/null || { echo 'XcodeGen is missing.' >&2; exit 1; }
bash iosApp/scripts/generate-project.sh "$@"
mkdir -p .local/ios-ci
if [[ "$method" == xcode-simulator ]]; then
  # Xcode owns Simulator packaging/sections/signing; no manual restricted claims.
  python3 scripts/ios_keychain_diagnostics.py build-xcode --artifacts .local/ios-ci --bundle "$bundle"
  exit 0
fi
xcodebuild \
  -project iosApp/BetterZslf.xcodeproj \
  -scheme BetterZslf \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath .local/ios-ci \
  -resultBundlePath .local/ios-ci/build.xcresult \
  "BETTER_ZSLF_EDITION=$edition" \
  "BETTER_ZSLF_BUNDLE_ID=$bundle" \
  "PRODUCT_BUNDLE_IDENTIFIER=$bundle" \
  CODE_SIGNING_ALLOWED=NO \
  build
