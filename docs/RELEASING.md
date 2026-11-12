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

1. Move the `[Unreleased]` entries in `CHANGELOG.md`, `CHANGELOG.de.md` and
   `CHANGELOG.es.md` into a new section `## [0.1.1] - 2026-10-03` in all
   three. The English section becomes the release notes and the text of the
   in-app update dialog (`scripts/release-notes.sh` extracts it). The German
   and Spanish ones follow in collapsed `<details lang="de">` and
   `<details lang="es">` blocks, which the app shows when its language is
   German or Spanish. App versions before 0.6.3 ignore those blocks.
   For a stable version, also write the short store notes (at most 500
   characters each) to
   `fastlane/metadata/android/{en-US,de-DE,es-ES}/changelogs/<versionCode>.txt`,
   for example `60399.txt` for 0.6.3.
2. Commit, then tag and push: `git tag v0.1.1 && git push origin main v0.1.1`.
3. The `Release` workflow builds the signed APK `glacier-androidtv.apk` and
   publishes the GitHub release; tags containing `-beta.` become
   pre-releases. The file name never changes, so
   `releases/latest/download/glacier-androidtv.apk` always points to the
   newest stable version (website and Downloader code use it). Releases up
   to 0.2.1 carry the old versioned name (0.2.1 has both), which the updater
   still accepts.

   The workflow uses no caches (Gradle and FFmpeg are built from scratch, so
   a release takes longer than a CI run) and attaches a build provenance
   attestation to the APK (see `SECURITY.md`).

### Raising the minimum server version

When a release needs a newer Jellyfin version (see
[ARCHITECTURE.md](ARCHITECTURE.md#server-compatibility)), change it in all of
these places and say in both changelogs that servers have to be updated:

- `MinimumServerVersion` in `core/jellyfin` `ServerInfo.kt`, and
  `ServerInfoTest.kt`
- `setup_unsupported_body` in `app/src/main/res/values{,-de,-es}/strings.xml`
- `README.md` (requirements table) and `docs/ARCHITECTURE.md`
- `fastlane/metadata/android/{en-US,de-DE,es-ES}/full_description.txt`

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

## Store versions

Glacier is also published in the Amazon Appstore and on Google Play. The
store versions come from their own build types, `amazon` (APK) and `play`
(app bundle). They are release builds with these differences:

- No self-updater: the store installs new versions. `BuildConfig.STORE` names
  the store, and `UpdaterConfig.selfUpdate` is off, so Glacier never asks
  GitHub. `src/store/AndroidManifest.xml` removes the permission to install
  apps.
- Instead of the update dialog, "What's new" shows the changes once after the
  store installed a newer version. The notes come from `CHANGELOG.md`, which
  the build copies into the app (`core/updater` `WhatsNew`).
- Settings › System shows the store with a button to its page, and the
  switch for "What's new".

Only stable tags build the store versions. The `Release` workflow leaves them
as the workflow artifact `store-builds` (kept for 30 days), never as release
assets: a store build installed by hand would never update.

### Uploading to the stores

After the GitHub release the workflow `stores.yml` waits for approval in the
GitHub environment `stores` (Actions › the run › Review deployments). Then it
uploads the bundle to Google Play and submits the APK to the Amazon Appstore
for review. The store notes come from
`fastlane/metadata/android/<language>/changelogs/<versionCode>.txt`
(`scripts/store-notes.sh`). The workflow checks them before it builds
anything, so a missing or too long one stops the release early. If one store
fails, the other is still tried.

Rerunning the failed job takes the files of the tag again. When the cause was
in the metadata (a listing text, a keyword), fix it on `main` and start
Actions › Stores › Run workflow on `main` instead: it takes the store builds
of the Release run given by its ID (the number in the run's URL) and the
metadata of `main`. Leave "Upload the app bundle" off when Google Play already
has the bundle of that version, since it takes each version code only once.
Amazon reuses the edit that is still open.

The environment holds the secrets and variables:

| Name | Kind | Value |
|---|---|---|
| `PLAY_SERVICE_ACCOUNT_JSON` | Secret | JSON key of a Google Cloud service account that may release Glacier and edit its store presence in the Play Console |
| `AMAZON_CLIENT_ID`, `AMAZON_CLIENT_SECRET` | Secret | Security profile linked to the App Submission API |
| `AMAZON_APP_ID` | Variable | App ID from the Amazon Developer Console (`amzn1.devportal.mobileapp.…`) |
| `PLAY_TRACK` | Variable | Google Play track, `alpha` (closed testing) if unset, later `production` |
| `PLAY_STATUS` | Variable | `completed` if unset. `draft` as long as the app was never published, which Google Play requires |

- Google Play: the upload uses the Play Developer API
  (`r0adkll/upload-google-play`) and sends the R8 mapping along for readable
  crash reports.
- Amazon: `scripts/amazon-submit.sh` first checks that every keyword is a
  single word, which Amazon requires. Then it opens an edit (or takes the one
  already open), replaces the APK, updates each listing (see below) and commits the
  edit, which starts Amazon's review. While a version is still in review,
  Amazon opens no new edit and the step fails with Amazon's message.
  Transient errors (500, 503, connection drops) are retried a few times
  before the step fails.

Google Play signs with the same key as the GitHub release (the existing
release key was handed over as the app signing key), so both versions can
update each other.

### Store listings

Texts and graphics of the listings live in `fastlane/metadata/`. After the
bundle, the workflow `stores.yml` updates the Google Play listing of each language
from `fastlane/metadata/android` (`scripts/play-listing.sh`, fastlane
supply): title, descriptions, video, feature graphic, icon, TV banner and TV
screenshots. The TV screenshots also go in as phone screenshots, which Google
Play requires even for a TV app. Only images Google Play does not have yet are uploaded, and
screenshots no longer in the folder are removed there. Changes in the Play
Console are overwritten with the next release, so change the files instead.

The Amazon submission fills each listing from the same files, with
`fastlane/metadata/amazon` for what only Amazon has (feature bullets,
keywords, small icon, Fire TV background) and the website's promo film
(`website/assets/promo/glacier-promo[-de].mp4`) as video. The Fire TV icon is
the TV banner, the promo image the feature graphic. Amazon has no checksums
to compare, so every submission replaces all images. Changes in the Amazon
Developer Console are overwritten as well. The video is the exception: Amazon's
gateway refuses video uploads through the API, so the step only warns. Add the
promo film once by hand in the Developer Console. A video that is already there
is kept.

- Screenshots (1920×1080, one set per language in
  `fastlane/metadata/android/{en-US,de-DE}/images/tvScreenshots/`) come from
  the takes of the promo video: `scripts/make-promo-video.py --takes DIR
  --only store`. They show the same moments as the website's section
  pictures. A language without its own images, such as `es-ES` for now,
  shows the English ones in both stores.
- Promo video: Google Play only takes a YouTube link. Upload
  `build/promo/glacier-promo[-de].mp4` to YouTube (public or unlisted, ads
  off, with the music credit from the end card in the description) and put
  the link into `fastlane/metadata/android/<language>/video.txt`.

### Trying a store version locally

A debug build plays a store version with `-Pglacier.store=amazon` or
`-Pglacier.store=play`. To see "What's new", start an older version once,
then install a newer one over it:

```sh
./gradlew :app:assembleDebug -Pglacier.version=0.6.1 -Pglacier.store=amazon
adb install -r app/build/outputs/apk/debug/app-debug.apk   # start it once
./gradlew :app:assembleDebug -Pglacier.version=0.6.2 -Pglacier.store=amazon
adb install -r app/build/outputs/apk/debug/app-debug.apk   # "What's new" for 0.6.2
```

The version of the last start sits in `shared_prefs/whats_new.xml`
(`last_version`). It only moves on once the dialog was shown.
