# Scripts

Helper scripts for building, releasing and publishing Glacier. Each script
finds the repository root from its own location, so you can run it from any
working directory.

The release scripts are called by `.github/workflows/release.yml` and
`.github/workflows/stores.yml`. The release workflow calls the stores
workflow, so both run as part of a release.

## ffmpeg

| Script | What it does | Run by |
|---|---|---|
| `build-ffmpeg.sh` | Builds FFmpeg's audio decoders for Android and installs their headers and static libraries where `core/ffmpeg` expects them. | CI and release workflows, through the FFmpeg action (`.github/actions/ffmpeg`); by hand on Linux, macOS or WSL |
| `check-ffmpeg-apk.sh` | Fails unless the APK contains the FFmpeg decoder for every ABI. | `ci.yml` and `release.yml` |

## release

| Script | What it does | Run by |
|---|---|---|
| `release-notes.sh` | Prints the changelog section of one version, with the German and Spanish sections below it, for the in-app update dialog. | `release.yml` |
| `store-notes.sh` | Copies the short store release notes of a stable version to an output folder, and fails if one is missing or longer than 500 characters. | `release.yml` and `stores.yml` |
| `play-listing.sh` | Updates the Google Play store listing of each language from `fastlane/metadata/android`. | `stores.yml` |
| `amazon-submit.sh` | Submits the Amazon APK with its store texts, graphics, screenshots and video through the Amazon App Submission API. | `stores.yml` |

## media

| Script | What it does | Run by |
|---|---|---|
| `capture-website-media.py` | Records the takes for the website and the promo video from the running app on an Android TV emulator. | By hand (needs an emulator and the demo server) |
| `make-promo-video.py` | Renders the promo video for the stores and the website from the recorded takes. | By hand |
| `make-playback-samples.py` | Makes the sample files for the playback check and for the Dolby Vision and IEC 61937 tests. | By hand (needs ffmpeg and dovi_tool) |
| `make-readme-badges.py` | Writes the README's wordmark and buttons as SVG files in `docs/badges/`, in light and dark. | By hand |
| `make-social-preview.py` | Writes the website's social preview image (`website/assets/social-preview.png`) from the home screen still (`website/assets/screenshots/home.jpg`). | By hand |

## website

| Script | What it does | Run by |
|---|---|---|
| `website-languages.py` | Writes the language menu into every page of the website from one table. With `--check` it only fails if a page is out of date. | By hand |
