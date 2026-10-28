#!/usr/bin/env bash
# Prints the CHANGELOG.md section for one version, without its heading, and
# below it the CHANGELOG.de.md section as a collapsed HTML block. The in-app
# update dialog renders exactly this text, so a release must not be published
# without a matching English section. The German block is HTML so that app
# versions before 0.6.3, which only read the Markdown, leave it out.
#
# Usage: scripts/release-notes.sh 1.4.0-beta.2
set -euo pipefail

version="${1:?usage: release-notes.sh <version>}"
root="$(dirname "$0")/.."

section() {
  [ -f "$1" ] || return 0
  awk -v heading="## [$version]" '
    index($0, heading) == 1 { found = 1; next }
    found && /^## \[/        { exit }
    found                    { print }
  ' "$1"
}

notes="$(section "$root/CHANGELOG.md")"
if [ -z "$(echo "$notes" | tr -d '[:space:]')" ]; then
  echo "No CHANGELOG.md section found for [$version]" >&2
  exit 1
fi
printf '%s\n' "$notes" | sed -e '/./,$!d'

german="$(section "$root/CHANGELOG.de.md")"
if [ -n "$(echo "$german" | tr -d '[:space:]')" ]; then
  printf '\n<details lang="de">\n<summary>Deutsch</summary>\n\n'
  # "### Heading" becomes <h3>, "- item" and its indented continuation lines one <li>.
  printf '%s\n' "$german" | awk '
    function esc(s) { gsub(/&/, "\\&amp;", s); gsub(/</, "\\&lt;", s); gsub(/>/, "\\&gt;", s); return s }
    function flush() { if (item != "") { print "<li>" esc(item) "</li>"; item = "" } }
    function close_list() { flush(); if (open) { print "</ul>"; open = 0 } }
    /^### /           { close_list(); print "<h3>" esc(substr($0, 5)) "</h3>"; next }
    /^[-*] /          { flush(); if (!open) { print "<ul>"; open = 1 } item = substr($0, 3); next }
    /^[ \t]+[^ \t]/   { if (item != "") { sub(/^[ \t]+/, ""); item = item " " $0 } next }
                      { close_list() }
    END               { close_list() }
  '
  printf '</details>\n'
fi
