#!/usr/bin/env bash
# The only persisted version source is the repository-root version.properties.
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ios_dir="$(CDPATH= cd -- "$script_dir/.." && pwd)"
repo_root="$(CDPATH= cd -- "$ios_dir/.." && pwd)"
properties="$repo_root/version.properties"

if [[ ! -f "$properties" ]]; then
  printf '%s\n' 'Missing root version.properties.' >&2
  exit 1
fi

read_property() {
  awk -v wanted="$1" '
    { sub(/\r$/, "") }
    /^[[:space:]]*([#!]|$)/ { next }
    {
      separator = index($0, "=")
      if (separator == 0) next
      key = substr($0, 1, separator - 1)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
      if (key != wanted) next
      value = substr($0, separator + 1)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      count++
    }
    END {
      if (count != 1 || value == "") exit 1
      print value
    }
  ' "$properties"
}

version_name="$(read_property VERSION_NAME)" || {
  printf '%s\n' 'Root version.properties must contain exactly one VERSION_NAME.' >&2
  exit 1
}
version_code="$(read_property VERSION_CODE)" || {
  printf '%s\n' 'Root version.properties must contain exactly one VERSION_CODE.' >&2
  exit 1
}
# Match Gradle precedence: explicit -P arguments override ORG_GRADLE_PROJECT_* environment values.
version_name="${ORG_GRADLE_PROJECT_versionName:-$version_name}"
version_code="${ORG_GRADLE_PROJECT_versionCode:-$version_code}"
for argument in "$@"; do
  case "$argument" in
    -PversionName=*) version_name="${argument#-PversionName=}" ;;
    -PversionCode=*) version_code="${argument#-PversionCode=}" ;;
    *) printf '%s\n' 'Expected only -PversionName=... or -PversionCode=... arguments.' >&2; exit 2 ;;
  esac
done

# iOS CFBundleShortVersionString is a three-integer release version, not a prerelease label.
# Validate before writing xcconfig so command/config injection is never evaluated.
if [[ ! "$version_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  printf '%s\n' 'iOS versionName must have the form major.minor.patch (integers only).' >&2
  exit 2
fi
if [[ ! "$version_code" =~ ^[1-9][0-9]*$ ]]; then
  printf '%s\n' 'versionCode must be a positive decimal integer.' >&2
  exit 2
fi

mkdir -p "$ios_dir/.generated"
output="$ios_dir/.generated/Version.xcconfig"
temporary="$(mktemp "$ios_dir/.generated/Version.xcconfig.XXXXXX")"
trap 'rm -f "$temporary"' EXIT
{
  printf '%s\n' '// Generated from root version.properties plus optional Gradle-compatible overrides.'
  printf '%s\n' '// Do not edit or commit; rerun iosApp/scripts/generate-project.sh after version changes.'
  printf 'BETTER_ZSLF_VERSION_NAME = %s\n' "$version_name"
  printf 'BETTER_ZSLF_VERSION_CODE = %s\n' "$version_code"
} > "$temporary"
if [[ ! -f "$output" ]] || ! cmp -s "$temporary" "$output"; then
  mv -f "$temporary" "$output"
fi
printf '%s\n' 'Prepared iOS version settings from the shared version source.'
