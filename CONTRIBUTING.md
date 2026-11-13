# Contributing to Glacier

Thanks for your interest in Glacier. Glacier is maintained by one person, who
reviews every change and decides what goes in. This document explains how to
help without anyone's time going to waste. Everyone taking part is expected
to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

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
- New features and bug fixes come with automated tests where practical, as unit
  tests under the module's `src/test`.
- Test focus and navigation with a D-pad (remote or emulator), not with a mouse.
- Follow the style of the surrounding code. `.editorconfig` defines formatting.
- `./gradlew detekt` checks code style. `./gradlew detekt -PdetektAutoCorrect` fixes formatting findings automatically.
- The fuzz tests in `fuzz/` run on their saved inputs as part of `testDebugUnitTest`.
  To fuzz for real, run one test class at a time with
  `JAZZER_FUZZ=1 ./gradlew :fuzz:test --tests '*TracksFuzzTest'`, which saves crashing inputs next to the seeds.
- Keep a pull request focused on one change.
- New third-party files (fonts, images, code or test data from other projects) need an entry in [REUSE.toml](REUSE.toml) with their licence; CI runs `reuse lint`.

The maintainer takes care of the changelog, releases and the
[website](website/); pull requests for the website are not accepted.

[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) describes the module layout.

## Translations

Glacier is available in English, German and Spanish, and follows the language
of the TV. To add a language:

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
