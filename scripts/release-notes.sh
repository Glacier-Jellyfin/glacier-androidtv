#!/usr/bin/env bash
# Prints the CHANGELOG.md section for one version, without its heading.
# The in-app update dialog renders exactly this text, so a release must not be
# published without a matching section.
#
# Usage: scripts/release-notes.sh 1.4.0-beta.2
set -euo pipefail

version="${1:?usage: release-notes.sh <version>}"
changelog="$(dirname "$0")/../CHANGELOG.md"

notes="$(awk -v heading="## [$version]" '
  index($0, heading) == 1 { found = 1; next }
  found && /^## \[/        { exit }
  found                    { print }
' "$changelog")"

if [ -z "$(echo "$notes" | tr -d '[:space:]')" ]; then
  echo "No CHANGELOG.md section found for [$version]" >&2
  exit 1
fi

printf '%s\n' "$notes" | sed -e '/./,$!d'
