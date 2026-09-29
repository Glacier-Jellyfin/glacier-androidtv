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
   `https://api.github.com/repos/Glacier-Jellyfin/glacier-androidtv/releases`
   (Settings › System can switch this off and check by hand). The result is
   kept until the next check, so an update found in the morning is still
   offered in the evening.
2. It picks the newest release its channel allows: Stable ignores
   pre-releases, Beta considers all. A release is skipped when its tag does not
   parse, when the pre-release flag disagrees with the tag, or when the APK
   asset is missing. Downgrades never happen: after switching from Beta back
   to Stable, the app waits for the next stable release.
3. The update dialog on the home screen shows the release notes, once per
   version; Settings › System shows them too. Only after the user confirms is
   the APK downloaded.
4. Before installing, the app checks the SHA-256 digest reported by GitHub
   (a release without one is refused), the package name and the
   `versionCode`. Android itself rejects APKs signed with a different key.
5. The system package installer performs the installation. Android asks once
   for permission to install apps from Glacier, and ends Glacier while it
   replaces it. Only Android 9 lets Glacier open itself again afterwards;
   newer versions block that, so the texts say Glacier closes.

### Testing the updater locally

Debug builds can read their releases from a local copy instead of GitHub,
also from a `file://` address inside the app's own storage:

```sh
# 1. The "new" version: note its SHA-256 and size for the feed.
./gradlew :app:assembleDebug -Pglacier.version=0.1.0-beta.2
cp app/build/outputs/apk/debug/app-debug.apk glacier-androidtv-0.1.0-beta.2.apk

# 2. The installed version, reading the local feed.
pkg=io.github.glacier_jellyfin.androidtv.debug
./gradlew :app:assembleDebug "-Pglacier.updateFeed=file:///data/user/0/$pkg/files/updtest/releases.json"
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. Feed and APK into the app's storage.
adb push releases.json glacier-androidtv-0.1.0-beta.2.apk /data/local/tmp/
adb shell "run-as $pkg mkdir -p files/updtest && run-as $pkg cp /data/local/tmp/releases.json /data/local/tmp/glacier-androidtv-0.1.0-beta.2.apk files/updtest/"
```

`releases.json` has the shape of the GitHub API response: `tag_name`,
`prerelease`, `published_at`, `body` and `assets` with `name`,
`browser_download_url` (here a `file://` address in `files/updtest/`),
`size` and `digest` (`sha256:<hex>`). Deleting `shared_prefs/updater.xml`
(with `run-as`) makes the app check again at the next start.
