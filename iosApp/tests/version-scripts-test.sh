#!/usr/bin/env bash
# Offline integration tests. All fixtures and stubs stay under ignored iosApp/.generated.
set -euo pipefail
script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ios_dir="$(CDPATH= cd -- "$script_dir/.." && pwd)"
mkdir -p "$ios_dir/.generated"
work="$(mktemp -d "$ios_dir/.generated/version-tests.XXXXXX")"
trap 'rm -rf "$work"' EXIT
fixture="$work/fixture with spaces"
mkdir -p "$fixture/iosApp/scripts" "$work/bin"
cp "$ios_dir/scripts/prepare-version.sh" "$ios_dir/scripts/embed-shared-framework.sh" \
  "$ios_dir/scripts/generate-project.sh" "$fixture/iosApp/scripts/"
prepare="$fixture/iosApp/scripts/prepare-version.sh"
embed="$fixture/iosApp/scripts/embed-shared-framework.sh"
generate="$fixture/iosApp/scripts/generate-project.sh"
config="$fixture/iosApp/.generated/Version.xcconfig"
count=0

pass() { count=$((count + 1)); printf 'PASS %s\n' "$1"; }
fail() { printf 'FAIL %s\n' "$1" >&2; exit 1; }
contains_line() { grep -Fqx -- "$2" "$1" || fail "$3"; }
expect_failure() {
  if "$@" >"$work/expected-failure.log" 2>&1; then
    fail 'expected validation failure'
  fi
}
reset_version() { printf 'VERSION_NAME=0.1.0\nVERSION_CODE=1\n' > "$fixture/version.properties"; }
# Do not inherit CI overrides while testing defaults.
unset ORG_GRADLE_PROJECT_versionName ORG_GRADLE_PROJECT_versionCode OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED

reset_version
/bin/bash "$prepare" >/dev/null
contains_line "$config" 'BETTER_ZSLF_VERSION_NAME = 0.1.0' 'root versionName'
contains_line "$config" 'BETTER_ZSLF_VERSION_CODE = 1' 'root versionCode'
pass 'root version source and paths containing spaces'

/bin/bash "$prepare" -PversionName=2.3.4 -PversionCode=42 >/dev/null
contains_line "$config" 'BETTER_ZSLF_VERSION_NAME = 2.3.4' 'CLI versionName override'
contains_line "$config" 'BETTER_ZSLF_VERSION_CODE = 42' 'CLI versionCode override'
pass 'Gradle-compatible CLI overrides'

ORG_GRADLE_PROJECT_versionName=3.4.5 ORG_GRADLE_PROJECT_versionCode=43 /bin/bash "$prepare" >/dev/null
contains_line "$config" 'BETTER_ZSLF_VERSION_NAME = 3.4.5' 'environment versionName'
contains_line "$config" 'BETTER_ZSLF_VERSION_CODE = 43' 'environment versionCode'
pass 'Gradle-compatible environment overrides'

ORG_GRADLE_PROJECT_versionName=3.4.5 ORG_GRADLE_PROJECT_versionCode=43 \
  /bin/bash "$prepare" -PversionName=4.5.6 -PversionCode=44 >/dev/null
contains_line "$config" 'BETTER_ZSLF_VERSION_NAME = 4.5.6' 'CLI precedence'
contains_line "$config" 'BETTER_ZSLF_VERSION_CODE = 44' 'CLI precedence'
pass 'CLI overrides environment'

printf '# comment\r\n VERSION_NAME = 5.6.7 \r\nVERSION_CODE=45\r\n' > "$fixture/version.properties"
/bin/bash "$prepare" >/dev/null
contains_line "$config" 'BETTER_ZSLF_VERSION_NAME = 5.6.7' 'CRLF parsing'
contains_line "$config" 'BETTER_ZSLF_VERSION_CODE = 45' 'CRLF parsing'
pass 'CRLF comments and whitespace'

reset_version
printf 'VERSION_NAME=9.9.9\n' >> "$fixture/version.properties"
expect_failure /bin/bash "$prepare"
pass 'duplicate root properties fail'

printf 'VERSION_NAME=1.2.3\n' > "$fixture/version.properties"
expect_failure /bin/bash "$prepare"
pass 'missing root properties fail'

reset_version
expect_failure /bin/bash "$prepare" -PversionName=1.2.3-beta
pass 'non-Apple release version fails'
expect_failure /bin/bash "$prepare" '-PversionName=$(touch injected)'
[[ ! -e "$fixture/injected" ]] || fail 'version evaluated as shell input'
pass 'config and shell injection fails'
expect_failure /bin/bash "$prepare" -PversionCode=0
expect_failure /bin/bash "$prepare" -PversionCode=-1
expect_failure /bin/bash "$prepare" -PversionCode=1.5
pass 'non-positive or non-integer build version fails'
expect_failure /bin/bash "$prepare" --unexpected
pass 'unknown argument fails'

# A fake xcodegen verifies orchestration without pretending to generate an Xcode project.
cat > "$work/bin/xcodegen" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$XCODEGEN_CAPTURE"
STUB
chmod +x "$work/bin/xcodegen"
export XCODEGEN_CAPTURE="$work/xcodegen-arguments.txt"
PATH="$work/bin:$PATH" /bin/bash "$generate" -PversionName=6.7.8 -PversionCode=46 >/dev/null
contains_line "$config" 'BETTER_ZSLF_VERSION_NAME = 6.7.8' 'generation version propagation'
contains_line "$XCODEGEN_CAPTURE" "$fixture/iosApp/project.yml" 'XcodeGen spec path'
contains_line "$XCODEGEN_CAPTURE" "$fixture/iosApp" 'XcodeGen output path'
pass 'project generation prepares version before invoking XcodeGen (stub)'

cat > "$fixture/gradlew" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$GRADLE_CAPTURE"
STUB
export GRADLE_CAPTURE="$work/gradle-arguments.txt"
PLATFORM_NAME=iphonesimulator CONFIGURATION=Debug ARCHS=arm64 MARKETING_VERSION=6.7.8 \
  CURRENT_PROJECT_VERSION=46 /bin/bash "$embed"
contains_line "$GRADLE_CAPTURE" ':shared:embedAndSignAppleFrameworkForXcode' 'Gradle task'
contains_line "$GRADLE_CAPTURE" '-PversionName=6.7.8' 'Gradle versionName propagation'
contains_line "$GRADLE_CAPTURE" '-PversionCode=46' 'Gradle versionCode propagation'
pass 'Xcode resolved versions forward unchanged to Gradle (stub)'

expect_failure env PLATFORM_NAME=iphonesimulator CONFIGURATION=Debug ARCHS=x86_64 \
  MARKETING_VERSION=6.7.8 CURRENT_PROJECT_VERSION=46 /bin/bash "$embed"
pass 'unsupported simulator architecture fails clearly'
expect_failure env PLATFORM_NAME=iphoneos CONFIGURATION=Debug ARCHS=arm64 \
  MARKETING_VERSION='' CURRENT_PROJECT_VERSION=46 /bin/bash "$embed"
pass 'missing Xcode version settings fail'

rm "$GRADLE_CAPTURE"
OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES /bin/bash "$embed" >/dev/null
[[ ! -e "$GRADLE_CAPTURE" ]] || fail 'IDE guard reran Gradle'
pass 'official IDE integration guard skips duplicate Gradle invocation'
printf 'All %s offline iOS script tests passed; no native compilation was performed.\n' "$count"
