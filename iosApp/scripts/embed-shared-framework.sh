#!/usr/bin/env bash
# Invoked before Compile Sources by Xcode. Shared.framework is static; do not add a Copy Frameworks phase.
set -euo pipefail

if [[ "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-NO}" == YES ]]; then
  printf '%s\n' 'Kotlin framework build is supplied by the IDE.'
  exit 0
fi

: "${PLATFORM_NAME:?Run this script from the Xcode build phase.}"
: "${CONFIGURATION:?Xcode must provide CONFIGURATION.}"
: "${MARKETING_VERSION:?Generate the project with iosApp/scripts/generate-project.sh first.}"
: "${CURRENT_PROJECT_VERSION:?Generate the project with iosApp/scripts/generate-project.sh first.}"
case "$PLATFORM_NAME" in
  iphoneos|iphonesimulator) ;;
  *) printf '%s\n' 'Only iOS device and iOS Simulator builds are configured.' >&2; exit 2 ;;
esac
script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(CDPATH= cd -- "$script_dir/../.." && pwd)"
if [[ "${ARCHS:-arm64}" != arm64 ]]; then
  printf '%s\n' 'The shared module and app currently support arm64 devices and arm64 simulators.' >&2
  exit 2
fi
if [[ ! "$MARKETING_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
   [[ ! "$CURRENT_PROJECT_VERSION" =~ ^[1-9][0-9]*$ ]]; then
  printf '%s\n' 'Invalid Xcode version settings; regenerate from the shared version source.' >&2
  exit 2
fi

# Xcode's resolved edition wins over the ambient Gradle environment, like version settings.
edition="${BETTER_ZSLF_EDITION-${ORG_GRADLE_PROJECT_betterZslfEdition-pro}}"
case "$edition" in
  standard) bundle=io.github.betterzslf ;;
  pro) bundle=io.github.betterzslf.pro ;;
  *) printf '%s\n' 'betterZslfEdition must be standard or pro.' >&2; exit 2 ;;
esac
if [[ "${PRODUCT_BUNDLE_IDENTIFIER-$bundle}" != "$bundle" ]] ||
   [[ "${BETTER_ZSLF_BUNDLE_ID-$bundle}" != "$bundle" ]]; then
  printf '%s\n' 'Xcode edition and bundle identifier disagree; regenerate the project.' >&2
  exit 2
fi

cd "$repo_root"
# The Xcode project settings must agree with the root version source, including an optional rcN
# prerelease that stays out of Apple's numeric CFBundleShortVersionString. Gradle configures Android
# too, so the full display version (base + rcN + edition suffix) is what it validates and records.
bridge="$(python3 "$repo_root/iosApp/scripts/version_settings.py" xcode)" || exit 2
IFS=$'\t' read -r gradle_version_name resolved_code resolved_edition <<< "$bridge"
if [[ "$resolved_code" != "$CURRENT_PROJECT_VERSION" || "$resolved_edition" != "$edition" ]]; then
  printf '%s\n' 'Xcode version or edition settings disagree with the shared version source; regenerate the project.' >&2
  exit 2
fi
# Do not enable shell tracing or print the Xcode environment (it may contain signing credentials).
exec /bin/bash "$repo_root/gradlew" :shared:embedAndSignAppleFrameworkForXcode \
  "-PversionName=$gradle_version_name" "-PversionCode=$resolved_code" "-PbetterZslfEdition=$resolved_edition"
