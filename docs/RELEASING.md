# Releasing

## Versions

Glacier uses `MAJOR.MINOR.PATCH` for stable releases and
`MAJOR.MINOR.PATCH-beta.N` for betas. No other pre-release forms exist.

Android's `versionCode` is derived from the version so that it always
increases, including from a beta to its stable release:

```
versionCode = major * 1_000_000 + minor * 10_000 + patch * 100 + (N for beta.N, 99 for stable)
```

| Version | versionCode |
|---|---|
| `1.4.0-beta.1` | 1040001 |
| `1.4.0-beta.2` | 1040002 |
| `1.4.0` | 1040099 |

Limits: `minor` and `patch` ≤ 99, `N` between 1 and 98. The rule exists twice,
in `app/build.gradle.kts` and in `core/updater` (`AppVersion`); keep them identical.

## Publishing a release

1. Move the `[Unreleased]` entries in `CHANGELOG.md` into a new section
   `## [1.4.0-beta.2] - 2026-09-26`. The section becomes the release notes
   and the text of the in-app update dialog.
2. Commit, then tag and push:
   ```sh
   git tag v1.4.0-beta.2
   git push origin v1.4.0-beta.2
   ```
3. The `Release` workflow builds the signed APK
   `glacier-androidtv-1.4.0-beta.2.apk` and publishes the GitHub release.
   Tags containing `-beta.` are published as pre-releases.

## Signing key

The release keystore never enters the repository. Create it once:

```sh
keytool -genkeypair -v -keystore glacier-release.jks -alias glacier \
  -keyalg RSA -keysize 4096 -validity 36500
```

Store it in a safe place with a backup: **if the key is lost, installed apps
can no longer be updated** and every user has to uninstall and reinstall.

Add these repository secrets (Settings › Secrets and variables › Actions):

| Secret | Value |
|---|---|
| `GLACIER_KEYSTORE_BASE64` | `base64 -w0 glacier-release.jks` |
| `GLACIER_KEYSTORE_PASSWORD` | Keystore password |
| `GLACIER_KEY_ALIAS` | `glacier` |
| `GLACIER_KEY_PASSWORD` | Key password |

## How the app updates itself

1. On start, at most once a day, the app reads
   `https://api.github.com/repos/Glacier-Jellyfin/glacier-androidtv/releases`.
2. It picks the newest release its channel allows: Stable ignores
   pre-releases, Beta considers all. A release is skipped when its tag does not
   parse, when the pre-release flag disagrees with the tag, or when the APK
   asset is missing. Downgrades never happen: after switching from Beta back
   to Stable, the app waits for the next stable release.
3. The update dialog shows the release notes. Only after the user confirms is
   the APK downloaded.
4. Before installing, the app checks the SHA-256 digest reported by GitHub,
   the package name and the `versionCode`. Android itself rejects APKs signed
   with a different key.
5. The system package installer performs the installation. Android asks once
   for permission to install apps from Glacier.
