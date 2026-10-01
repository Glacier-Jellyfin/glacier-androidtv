# Contributing to Glacier

Thanks for your interest in Glacier. This document covers the few rules that
keep the project consistent.

## Language

Everything in this repository is written in English: code, identifiers,
comments, documentation, commit messages and release notes. User-facing
strings are the only exception; translations live in `values-<lang>/strings.xml`.

## Before you start

For anything larger than a small fix, open an issue first so the approach can
be agreed on before you invest time.

## Development

- Build and test with `./gradlew testDebugUnitTest lintDebug assembleDebug`;
  CI runs the same command on every pull request.
- Test focus and navigation with a D-pad (remote or emulator), not with a mouse.
- Follow the style of the surrounding code. `.editorconfig` defines formatting.

## Website screenshots and video

`scripts/capture-website-media.py` refreshes every screenshot and the demo
video in `website/assets/screenshots/` from a debug build on a 1920x1080 TV
emulator. Sign in to the Jellyfin demo server (`https://demo.jellyfin.org/stable`,
user `demo`, no password) first, keep the app in English with the default
accent, then run the script; it needs Python 3, adb and ffmpeg. Only use the
demo server's public domain library for published pictures.

## Commits and pull requests

- Use [Conventional Commits](https://www.conventionalcommits.org/)
  (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `build:`, `ci:`, `chore:`).
- Keep pull requests focused on one change.
- Add a line to the `[Unreleased]` section of `CHANGELOG.md` for user-visible changes.

## License

By contributing, you agree that your contributions are licensed under the
GNU General Public License v3.0 or later.
