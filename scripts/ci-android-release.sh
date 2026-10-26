#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
# Do not enable shell tracing: signing credentials remain in environment only.
: "${RELEASE_VERSION_NAME:?Missing validated release version name}"
: "${RELEASE_VERSION_CODE:?Missing validated release version code}"
: "${ANDROID_KEYSTORE_PATH:?Missing prepared release keystore path}"
: "${ANDROID_KEYSTORE_PASSWORD:?Missing release keystore password}"
: "${ANDROID_KEY_ALIAS:?Missing release key alias}"
: "${ANDROID_KEY_PASSWORD:?Missing release key password}"
: "${ANDROID_EXPECTED_CERT_SHA256:?Missing validated signing certificate}"
[[ -s "$ANDROID_KEYSTORE_PATH" ]] || { echo 'Prepared release keystore is missing or empty.' >&2; exit 1; }
edition="${BETTER_ZSLF_EDITION:-standard}"
case "$edition" in standard|pro) ;; *) echo 'Edition must be standard or pro.' >&2; exit 1 ;; esac
python3 scripts/verify-wrapper.py
# The aggregate covers all enabled app unit-test variants, including minified-build setups.
bash ./gradlew --no-daemon --stacktrace \
  "-PversionName=$RELEASE_VERSION_NAME" "-PversionCode=$RELEASE_VERSION_CODE" "-PbetterZslfEdition=$edition" \
  :shared:jvmTest \
  :androidApp:test \
  :androidApp:lintRelease \
  :androidApp:assembleRelease \
  :androidApp:bundleRelease
python3 scripts/verify_release.py --edition "$edition" --metadata .local/release-version.json --output .local/release-dist
