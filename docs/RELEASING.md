# Releasing

## Versions

Glacier uses `MAJOR.MINOR.PATCH` for stable releases and
`MAJOR.MINOR.PATCH-beta.N` for betas. No other pre-release forms exist.

- **Patch releases** (`0.1.1`, `0.1.2`) are the normal case: fixes and small
  improvements go out directly as stable releases, without a beta.
- **Minor releases** (`0.2.0`) bring new features. Only before one of them,
  and only when something larger should run on real devices first, are there
  one or two betas (`0.2.0-beta.1`).
- The Beta update channel therefore stays empty most of the time; it offers
  every stable release as well.

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

## How the app updates itself

1. On every start the app reads
   `https://api.github.com/repos/Glacier-Jellyfin/glacier-androidtv/releases`
   (Settings › System can switch this off and check by hand). The last result
   stands in while the check runs and when it fails (offline start).
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
`size` and `digest` (`sha256:<hex>`). The app checks again at every start.

## Publishing a release (maintainer only)

1. Move the `[Unreleased]` entries in `CHANGELOG.md` into a new section
   `## [0.1.1] - 2026-10-03`. The section becomes the release notes and the
   text of the in-app update dialog (`scripts/release-notes.sh` extracts it).
2. Commit, then tag and push: `git tag v0.1.1 && git push origin main v0.1.1`.
3. The `Release` workflow builds the signed APK `glacier-androidtv.apk` and
   publishes the GitHub release; tags containing `-beta.` become
   pre-releases. The file name never changes, so
   `releases/latest/download/glacier-androidtv.apk` always points to the
   newest stable version (website and Downloader code use it). Releases up
   to 0.2.1 also carry the old versioned name, which the updater still
   accepts.

### Signing key

The release keystore (`keytool -genkeypair -keystore glacier-release.jks
-alias glacier -keyalg RSA -keysize 4096 -validity 36500`) never enters the
repository. Keep it with a backup: **if the key is lost, installed apps can no
longer be updated** and every user has to uninstall and reinstall.

The workflow reads it from these repository secrets:

| Secret | Value |
|---|---|
| `GLACIER_KEYSTORE_BASE64` | `base64 -w0 glacier-release.jks` |
| `GLACIER_KEYSTORE_PASSWORD` | Keystore password |
| `GLACIER_KEY_ALIAS` | `glacier` |
| `GLACIER_KEY_PASSWORD` | Key password |
