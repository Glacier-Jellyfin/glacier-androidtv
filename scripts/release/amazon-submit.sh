#!/usr/bin/env bash
# Submits the Amazon APK to the Amazon Appstore through the App Submission API.
# It opens an edit, which starts as a copy of the live version, or takes the
# one already open, and replaces its APK. Each listing gets "Recent changes"
# from <notes-dir>/whatsnew-<language> (see store-notes.sh) and its texts,
# graphics, screenshots and video from fastlane/metadata and the website's
# promo film. Committing the edit sends it to Amazon's review. See
# docs/RELEASING.md.
#
# Usage: scripts/release/amazon-submit.sh <apk> <notes-dir>
# Needs AMAZON_CLIENT_ID, AMAZON_CLIENT_SECRET and AMAZON_APP_ID.
set -euo pipefail
shopt -s inherit_errexit

apk="${1:?usage: amazon-submit.sh <apk> <notes-dir>}"
notes="${2:?usage: amazon-submit.sh <apk> <notes-dir>}"
: "${AMAZON_CLIENT_ID:?}" "${AMAZON_CLIENT_SECRET:?}" "${AMAZON_APP_ID:?AMAZON_APP_ID is not set, see docs/RELEASING.md}"
api="https://developer.amazon.com/api/appstore/v1/applications/$AMAZON_APP_ID"
metadata="$(dirname "$0")/../../fastlane/metadata"

# Amazon takes only single words as keywords and refuses the whole listing otherwise,
# after the APK is already replaced. Check before anything is sent.
for file in "$metadata"/amazon/*/keywords.txt; do
  if tr ',' '
' < "$file" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | grep -q '[[:space:]]'; then
    echo "::error::$file: Amazon takes only single words as keywords, separated by commas" >&2
    exit 1
  fi
done

headers="$(mktemp)"
body="$(mktemp)"

# send WHAT [curl options]: sends one request into $body and sets $status. Amazon's API
# now and then answers 500 for no reason, so a transient failure is tried again, 5 attempts
# in all, waiting 5, 10, 20 and 40 seconds. Transient are 429, 500, 502, 503, 504 and
# status 000, which is a connection that broke or timed out. Any other answer is final.
# AMAZON_RETRY_WAIT sets the first wait in seconds. $retried tells if it tried again.
send() {
  local what="$1" attempt=1 wait="${AMAZON_RETRY_WAIT:-5}"
  shift
  retried=0
  while :; do
    # curl fails on a broken connection, which must lead to a retry and not end the script.
    status="$(curl -sS -o "$body" -w '%{http_code}' "$@")" || status=000
    case "$status" in
      000 | 429 | 500 | 502 | 503 | 504) ;;
      *) return 0 ;;
    esac
    [ "$attempt" -lt 5 ] || return 0
    echo "::warning::Amazon answered $what with $status, trying again in ${wait}s (attempt $((attempt + 1)) of 5)" >&2
    sleep "$wait"
    wait=$((wait * 2))
    attempt=$((attempt + 1))
    retried=1
  done
}

send 'the sign-in request' https://api.amazon.com/auth/o2/token \
  --data-urlencode grant_type=client_credentials \
  --data-urlencode "client_id=$AMAZON_CLIENT_ID" \
  --data-urlencode "client_secret=$AMAZON_CLIENT_SECRET" \
  --data-urlencode scope=appstore::apps:readwrite
if [ "$status" -ge 300 ] || [ "$status" = 000 ]; then
  # The answer names the cause, such as invalid_client or invalid_scope, and holds no secret.
  echo "::error::Amazon refused the sign-in with $status: $(cat "$body")" >&2
  exit 1
fi
token="$(jq -r .access_token "$body")"
echo "::add-mask::$token"

# call METHOD PATH [curl options]: prints the answer, fails with it on an HTTP error.
call() {
  local method="$1" path="$2"
  shift 2
  local status
  send "$method $path" -X "$method" -D "$headers" \
    -H "Authorization: Bearer $token" "$@" "$api$path"
  if [ "$status" -ge 300 ] || [ "$status" = 000 ]; then
    echo "::error::Amazon answered $method $path with $status: $(cat "$body")" >&2
    if [ "$status" = 412 ] && [ "$retried" = 1 ]; then
      # If-Match fails when an earlier attempt, which looked failed, was applied after all.
      echo "::error::An earlier attempt of this request may have gone through. Run the job again, which is safe because it reuses the open edit and replaces everything." >&2
    fi
    exit 1
  fi
  cat "$body"
}

# The ETag of the last answer, which a change of the same thing sends as If-Match.
etag() {
  grep -i '^etag:' "$headers" | cut -d' ' -f2- | tr -d '\r'
}

# While a version is in review no edit can be opened, Amazon says so in the error.
edit="$(call GET /edits | jq -r '.id // empty')"
if [ -n "$edit" ]; then
  echo "Using the edit already open: $edit"
else
  edit="$(call POST /edits | jq -r .id)"
  echo "Opened edit $edit"
fi

apks="$(call GET "/edits/$edit/apks")"
if [ "$(jq length <<< "$apks")" != 1 ]; then
  echo "::error::The edit has $(jq length <<< "$apks") APKs, expected the one of the live version"
  exit 1
fi
apk_id="$(jq -r '.[0].id' <<< "$apks")"
call GET "/edits/$edit/apks/$apk_id" > /dev/null
call PUT "/edits/$edit/apks/$apk_id/replace" \
  -H "If-Match: $(etag)" \
  -H 'Content-Type: application/vnd.android.package-archive' \
  --data-binary "@$apk" > /dev/null
echo "Replaced the APK"

# The number of images or videos in a list answer.
count() {
  jq '[.. | objects | select(has("id"))] | length'
}

# upload PATH FILE: adds an image or video. Icons and backgrounds hold one, which it replaces.
upload() {
  local target="$1/upload"
  [[ "$1" != */videos ]] || target="$1"
  call GET "$1" > /dev/null
  call POST "$target" \
    -H "If-Match: $(etag)" \
    -H 'Content-Type: application/octet-stream' \
    --data-binary "@$2" > /dev/null
}

# clear_all PATH: removes all images of a type, or all videos.
clear_all() {
  if [ "$(call GET "$1" | count)" -gt 0 ]; then
    call DELETE "$1" -H "If-Match: $(etag)" > /dev/null
  fi
}

# A graphic of the language, or the English one if the language has none of its own.
graphic() {
  local file="$metadata/android/$language/images/$1"
  [ -f "$file" ] || file="$metadata/android/en-US/images/$1"
  echo "$file"
}

# The listing of each language, from the same files as the Google Play listing
# and fastlane/metadata/amazon. Amazon's edit keeps the images of the live
# version but has no checksums to compare, so all of them are replaced.
for file in "$notes"/whatsnew-*; do
  language="${file##*/whatsnew-}"
  path="/edits/$edit/listings/$language"
  if listing="$(call GET "$path" 2> /dev/null)"; then
    match=(-H "If-Match: $(etag)")
  else
    # A language the app has no listing in yet, such as a new translation: it is created.
    echo "No listing for $language yet, creating it"
    listing="$(jq -n --arg language "$language" '{language: $language}')"
    match=()
  fi
  jq --rawfile notes "$file" \
    --rawfile title "$metadata/android/$language/title.txt" \
    --rawfile short "$metadata/android/$language/short_description.txt" \
    --rawfile full "$metadata/android/$language/full_description.txt" \
    --rawfile bullets "$metadata/amazon/$language/feature_bullets.txt" \
    --rawfile keywords "$metadata/amazon/$language/keywords.txt" '
    .recentChanges = $notes
    | .title = ($title | rtrimstr("\n"))
    | .shortDescription = ($short | rtrimstr("\n"))
    | .fullDescription = ($full | rtrimstr("\n"))
    | .featureBullets = ($bullets | split("\n") | map(select(length > 0)))
    | .keywords = ($keywords | split(",") | map(gsub("^\\s+|\\s+$"; "")) | map(select(length > 0)))' \
    <<< "$listing" |
    call PUT "$path" \
      "${match[@]}" \
      -H 'Content-Type: application/json' \
      --data-binary @- > /dev/null
  echo "Set the texts and recent changes for $language"

  upload "$path/small-icons" "$metadata/amazon/images/smallIcon.png"
  upload "$path/large-icons" "$(graphic icon.png)"
  upload "$path/firetv-icons" "$(graphic tvBanner.png)"
  upload "$path/firetv-backgrounds" "$metadata/amazon/images/fireTvBackground.png"
  upload "$path/promo-images" "$(graphic featureGraphic.png)"
  echo "Replaced the icons, background and promo image for $language"

  # Amazon's check requires 3 to 10 screenshots in both lists, the Fire TV one and the general
  # one, in every language. A language without pictures of its own gets the English ones.
  shots="$metadata/android/$language/images/tvScreenshots"
  [ -d "$shots" ] || shots="$metadata/android/en-US/images/tvScreenshots"
  for type in firetv-screenshots screenshots; do
    clear_all "$path/$type"
    for shot in "$shots"/*.jpg; do
      upload "$path/$type" "$shot"
    done
    echo "Replaced the $type for $language"
  done

  # The promo film from the website, the one Google Play shows from YouTube. Amazon's
  # gateway refuses video uploads ("Unable to fetch the request scope"), although its
  # API reference lists them, so a failed one only warns: add the video in the console.
  # A video already there stays, so one added by hand is never removed.
  code="${language%%-*}"
  video="$(dirname "$0")/../../website/assets/promo/glacier-promo$([ "$code" = en ] || echo "-$code").mp4"
  if [ "$(call GET "$path/videos" | count)" -gt 0 ]; then
    echo "Kept the video for $language"
  elif [ -f "$video" ]; then
    if (upload "$path/videos" "$video") 2> "$body.video"; then
      echo "Added the video for $language"
    else
      echo "::warning::Amazon took no video for $language, add $video in the Developer Console: $(cat "$body.video")"
    fi
  fi
done

call POST "/edits/$edit/validate" > /dev/null
call GET "/edits/$edit" > /dev/null
call POST "/edits/$edit/commit" -H "If-Match: $(etag)" > /dev/null
echo "Submitted edit $edit for review"
