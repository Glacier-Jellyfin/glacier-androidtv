# Contributing to Glacier

Thanks for your interest in Glacier. Glacier is maintained by one person, who
reviews every change and decides what goes in. This document explains how to
help without anyone's time going to waste.

## Bugs and ideas

Use the [issue forms](https://github.com/Glacier-Jellyfin/glacier-androidtv/issues/new/choose).
For bugs, Settings › System › Diagnostics sends a cleaned error log to your
Jellyfin server; attaching it helps a lot.

## Pull requests

Pull requests are welcome. Small fixes can go straight to a pull request; for
anything larger, open an issue first and wait until the approach is agreed, so
that the work is not done for nothing. The maintainer reviews, may ask for
changes, and merges or closes the pull request.

- Build and test with `./gradlew testDebugUnitTest lintDebug assembleDebug`;
  CI runs the same command on every pull request.
- Test focus and navigation with a D-pad (remote or emulator), not with a mouse.
- Follow the style of the surrounding code. `.editorconfig` defines formatting.
- Keep a pull request focused on one change.

The maintainer takes care of the changelog, releases and the
[website](website/); pull requests for the website are not accepted.

[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) describes the module layout.

## Translations

Glacier is available in English and German, and follows the language of the
TV. To add a language:

1. Copy `app/src/main/res/values/strings.xml` to
   `app/src/main/res/values-<lang>/strings.xml`, where `<lang>` is the
   language code Android uses, such as `fr`, `pt-rBR` or `zh-rTW`.
2. Translate the texts. Leave out every string marked `translatable="false"`,
   keep placeholders such as `%1$s` or `%2$d` exactly as they are, and give
   `<plurals>` the quantities your language needs.
3. Open a pull request with that one file.

Partial translations are fine: texts that are missing appear in English.
Improvements to an existing translation are welcome the same way. Please say in
the pull request whether you are a native speaker.

## Language

Everything in this repository is written in English: code, identifiers,
comments, documentation, commit messages and release notes. User-facing
strings are the only exception; translations live in `values-<lang>/strings.xml`.

## License

By contributing, you agree that your contributions are licensed under the
GNU General Public License v3.0 or later.
