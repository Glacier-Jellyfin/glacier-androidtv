#!/usr/bin/env bash
# Updates the Google Play store listing of each language from
# fastlane/metadata/android: title, descriptions, promo video (video.txt),
# graphics and TV screenshots. Uses fastlane supply, which only uploads images
# whose checksum Google Play does not have yet and removes screenshots that no
# longer exist here. Builds and release notes are uploaded separately, so this
# runs after the bundle of <version> is in <track>. See docs/RELEASING.md.
#
# Usage: scripts/play-listing.sh <version> <track>
# Needs fastlane and PLAY_SERVICE_ACCOUNT_JSON.
set -euo pipefail

version="${1:?usage: play-listing.sh <version> <track>}"
track="${2:?usage: play-listing.sh <version> <track>}"
: "${PLAY_SERVICE_ACCOUNT_JSON:?}"
root="$(dirname "$0")/.."

if [[ ! "$version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  echo "Only stable versions go to the stores, not '$version'" >&2
  exit 1
fi
# Same scheme as versionCodeOf in app/build.gradle.kts, 99 for a stable release.
# supply needs it to pick the release in the track, even without changelogs.
code=$(( BASH_REMATCH[1] * 1000000 + BASH_REMATCH[2] * 10000 + BASH_REMATCH[3] * 100 + 99 ))

# The key goes through the environment, so it never shows up in the command line.
SUPPLY_JSON_KEY_DATA="$PLAY_SERVICE_ACCOUNT_JSON" FASTLANE_SKIP_UPDATE_CHECK=1 fastlane supply \
  --package_name io.github.glacier_jellyfin.androidtv \
  --metadata_path "$root/fastlane/metadata/android" \
  --track "$track" \
  --version_code "$code" \
  --skip_upload_apk true \
  --skip_upload_aab true \
  --skip_upload_changelogs true \
  --sync_image_upload true
