#!/usr/bin/env bash
# Submits the Amazon APK to the Amazon Appstore through the App Submission API.
# It opens an edit, which starts as a copy of the live version, or takes the
# one already open, replaces its APK and sets "Recent changes" of each listing
# from <notes-dir>/whatsnew-<language> (see store-notes.sh). Committing the
# edit sends it to Amazon's review. See docs/RELEASING.md.
#
# Usage: scripts/amazon-submit.sh <apk> <notes-dir>
# Needs AMAZON_CLIENT_ID, AMAZON_CLIENT_SECRET and AMAZON_APP_ID.
set -euo pipefail
shopt -s inherit_errexit

apk="${1:?usage: amazon-submit.sh <apk> <notes-dir>}"
notes="${2:?usage: amazon-submit.sh <apk> <notes-dir>}"
: "${AMAZON_CLIENT_ID:?}" "${AMAZON_CLIENT_SECRET:?}" "${AMAZON_APP_ID:?AMAZON_APP_ID is not set, see docs/RELEASING.md}"
api="https://developer.amazon.com/api/appstore/v1/applications/$AMAZON_APP_ID"

token="$(curl -fsS https://api.amazon.com/auth/o2/token \
  --data-urlencode grant_type=client_credentials \
  --data-urlencode "client_id=$AMAZON_CLIENT_ID" \
  --data-urlencode "client_secret=$AMAZON_CLIENT_SECRET" \
  --data-urlencode scope=appstore::apps:readwrite | jq -r .access_token)"
echo "::add-mask::$token"

headers="$(mktemp)"
body="$(mktemp)"

# call METHOD PATH [curl options]: prints the answer, fails with it on an HTTP error.
call() {
  local method="$1" path="$2"
  shift 2
  local status
  status="$(curl -sS -X "$method" -D "$headers" -o "$body" -w '%{http_code}' \
    -H "Authorization: Bearer $token" "$@" "$api$path")"
  if [ "$status" -ge 300 ]; then
    echo "::error::Amazon answered $method $path with $status: $(cat "$body")" >&2
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

for file in "$notes"/whatsnew-*; do
  language="${file##*/whatsnew-}"
  listing="$(call GET "/edits/$edit/listings/$language")"
  jq --rawfile notes "$file" '.recentChanges = $notes' <<< "$listing" |
    call PUT "/edits/$edit/listings/$language" \
      -H "If-Match: $(etag)" \
      -H 'Content-Type: application/json' \
      --data-binary @- > /dev/null
  echo "Set the recent changes for $language"
done

call POST "/edits/$edit/validate" > /dev/null
call GET "/edits/$edit" > /dev/null
call POST "/edits/$edit/commit" -H "If-Match: $(etag)" > /dev/null
echo "Submitted edit $edit for review"
