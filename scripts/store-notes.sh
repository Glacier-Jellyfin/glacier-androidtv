#!/usr/bin/env bash
# Collects the store release notes of a stable version: for each language in
# fastlane/metadata/android the file changelogs/<versionCode>.txt, written by
# hand because Google Play takes at most 500 characters. They are copied to
# <out-dir>/whatsnew-<language>, the names the Google Play upload expects,
# which the Amazon upload reads as well. Fails if one is missing or too long,
# so the release workflow stops before anything is published.
#
# Usage: scripts/store-notes.sh 1.4.0 <out-dir>
set -euo pipefail

version="${1:?usage: store-notes.sh <version> <out-dir>}"
out="${2:?usage: store-notes.sh <version> <out-dir>}"
root="$(dirname "$0")/.."

if [[ ! "$version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  echo "Only stable versions go to the stores, not '$version'" >&2
  exit 1
fi
# Same scheme as versionCodeOf in app/build.gradle.kts, 99 for a stable release
code=$(( BASH_REMATCH[1] * 1000000 + BASH_REMATCH[2] * 10000 + BASH_REMATCH[3] * 100 + 99 ))

mkdir -p "$out"
failed=0
for dir in "$root"/fastlane/metadata/android/*/; do
  language="$(basename "$dir")"
  file="$dir/changelogs/$code.txt"
  if [ ! -f "$file" ]; then
    echo "::error::Missing store notes fastlane/metadata/android/$language/changelogs/$code.txt"
    failed=1
    continue
  fi
  chars="$(LC_ALL=C.UTF-8 wc -m < "$file")"
  if [ "$chars" -gt 500 ]; then
    echo "::error::fastlane/metadata/android/$language/changelogs/$code.txt has $chars characters, Google Play takes 500"
    failed=1
    continue
  fi
  cp "$file" "$out/whatsnew-$language"
done
exit "$failed"
