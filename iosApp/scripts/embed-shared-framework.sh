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
if [[ "${ARCHS:-arm64}" != arm64 ]]; then
  printf '%s\n' 'The shared module and app currently support arm64 devices and arm64 simulators.' >&2
  exit 2
fi
if [[ ! "$MARKETING_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
   [[ ! "$CURRENT_PROJECT_VERSION" =~ ^[1-9][0-9]*$ ]]; then
  printf '%s\n' 'Invalid Xcode version settings; regenerate from the shared version source.' >&2
  exit 2
fi

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(CDPATH= cd -- "$script_dir/../.." && pwd)"
cd "$repo_root"
# Forward the exact resolved Xcode values so CI overrides apply to both builds identically.
# Do not enable shell tracing or print the Xcode environment (it may contain signing credentials).
exec /bin/bash "$repo_root/gradlew" :shared:embedAndSignAppleFrameworkForXcode \
  "-PversionName=$MARKETING_VERSION" "-PversionCode=$CURRENT_PROJECT_VERSION"
