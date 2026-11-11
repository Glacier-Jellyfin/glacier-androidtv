#!/usr/bin/env python3
"""Writes the language menu of the website into every page, from one table.

Each page holds the same menu, with its own language marked, so the entries
never move from page to page. The order follows one rule:

  1. English first, set apart by a divider line (class "source", see site.css): it is the language of
     the source texts and the one everybody finds at the top.
  2. Then every language written in Latin letters, alphabetically by its own name
     (accents and case ignored): Deutsch, Español, Français, Italiano, Nederlands, ...
  3. Then the other scripts, by language code: العربية (ar), 日本語 (ja), 한국어 (ko), Русский (ru), 中文 (zh), ...

To add a language:
  - add a row to LANGUAGES below and run this script (it rewrites the menu of all pages),
  - translate website/<code>/index.html and privacy.html, and add the <link rel="alternate" hreflang="<code>">
    to every page (this script says which pages lack it),
  - add the language to TEXT in website/assets/lang.js (the hint for visitors whose browser speaks it),
  - add its link to the footer of every page,
  - mention the language in the privacy policy only if its storage text changes.

Usage: scripts/website-languages.py [--check]   (--check changes nothing and fails if a page is out of date)
"""
from __future__ import annotations

import argparse
import re
import sys
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / "website"

# code, own name, folder (empty for the root), script, the word "Language" in that language
LANGUAGES = [
    ("en", "English", "", "latin", "Language"),
    ("de", "Deutsch", "de/", "latin", "Sprache"),
    ("es", "Español", "es/", "latin", "Idioma"),
]
PAGES = ("index", "privacy")

GLOBE = ('<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" '
         'stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M3 12h18"/>'
         '<path d="M12 3a14 14 0 0 1 0 18 14 14 0 0 1 0-18Z"/></svg>')
CHEVRON = ('<svg class="chevron" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="2" '
           'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m2 4 4 4 4-4"/></svg>')


def plain(name: str) -> str:
    """The name without accents and case, so that Español sorts as espanol."""
    decomposed = unicodedata.normalize("NFD", name)
    return "".join(c for c in decomposed if not unicodedata.combining(c)).casefold()


def ordered() -> list[tuple]:
    def key(language: tuple) -> tuple:
        code, name, _, script, _ = language
        if code == "en":
            return (0, "", "")
        if script == "latin":
            return (1, plain(name), code)
        return (2, code, "")
    return sorted(LANGUAGES, key=key)


def address(here: str, code: str, page: str) -> str:
    """The address of the same page in [code], seen from a page in [here]."""
    folders = {c: folder for c, _, folder, _, _ in LANGUAGES}
    if code == here:
        return "./" if page == "index" else "privacy.html"
    up = "../" if folders[here] else ""
    return (up + folders[code] + ("" if page == "index" else "privacy.html")) or "./"


def menu(here: str, page: str) -> str:
    names = {c: (n, label) for c, n, _, _, label in LANGUAGES}
    items = []
    for code, name, _, _, _ in ordered():
        current = ' aria-current="page"' if code == here else ""
        divided = ' class="source"' if code == "en" else ""
        items.append(f'          <li{divided}><a href="{address(here, code, page)}" hreflang="{code}" lang="{code}"{current}>{name}</a></li>')
    name_here, label = names[here]
    return "\n".join([
        '<details class="lang">',
        f'        <summary aria-label="{label}: {name_here}">{GLOBE}<span>{name_here}</span>{CHEVRON}</summary>',
        '        <ul>',
        *items,
        '        </ul>',
        '      </details>',
    ])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--check", action="store_true", help="change nothing, fail if a page is out of date")
    check = parser.parse_args().check
    stale = 0
    for here, _, folder, _, _ in LANGUAGES:
        for page in PAGES:
            path = SITE / folder / f"{page}.html"
            if not path.exists():
                print(f"missing page: {path.relative_to(ROOT)}")
                stale += 1
                continue
            text = path.read_text(encoding="utf-8")
            found = re.search(r'<details class="lang">[\s\S]*?</details>', text)
            if not found:
                print(f"no language menu in {path.relative_to(ROOT)}")
                stale += 1
                continue
            updated = text[: found.start()] + menu(here, page) + text[found.end():]
            for code, *_ in LANGUAGES:
                if code != here and f'rel="alternate" hreflang="{code}"' not in text:
                    print(f"{path.relative_to(ROOT)} lacks <link rel=\"alternate\" hreflang=\"{code}\">")
                    stale += 1
            if updated != text:
                stale += 1
                print(("out of date: " if check else "updated: ") + str(path.relative_to(ROOT)))
                if not check:
                    path.write_text(updated, encoding="utf-8", newline="\n")
    if not stale:
        print("all language menus are up to date")
    return 1 if check and stale else 0


if __name__ == "__main__":
    sys.exit(main())
