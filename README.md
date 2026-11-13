<p align="center">
  <br>
  <img src="docs/badges/wordmark.svg" height="84" alt="Glacier">
  <br>
  <br>
</p>

<h3 align="center">The TV is yours again.</h3>

<p align="center">
  Glacier brings your Jellyfin server to Android TV, Google TV and Fire TV.<br>
  Built for the remote, with the sound and picture your files really have.
</p>

<p align="center">
  <a href="https://www.amazon.com/dp/B0HPL2YVVQ"><img src="docs/badges/amazon.svg" height="56" alt="Get it from the Amazon Appstore for Fire TV"></a>
  <a href="#become-a-tester-on-google-play"><img src="docs/badges/play.svg" height="56" alt="Google Play: become a tester"></a>
  <a href="https://github.com/Glacier-Jellyfin/glacier-androidtv/releases/latest/download/glacier-androidtv.apk"><img src="docs/badges/github.svg" height="56" alt="Download the APK from GitHub"></a>
</p>

<p align="center">
  <a href="https://glacier-jellyfin.github.io/glacier-androidtv/">Website</a> ·
  <a href="#installation">Installation</a> ·
  <a href="CHANGELOG.md">Changelog</a> ·
  <a href="https://github.com/Glacier-Jellyfin/glacier-androidtv/issues">Report a bug</a>
</p>

<p align="center">
  <a href="https://github.com/Glacier-Jellyfin/glacier-androidtv/releases/latest"><img src="https://img.shields.io/github/v/release/Glacier-Jellyfin/glacier-androidtv?sort=semver&amp;style=flat&amp;label=release" alt="Latest release"></a>
  <a href="https://github.com/Glacier-Jellyfin/glacier-androidtv/releases"><img src="https://img.shields.io/github/downloads/Glacier-Jellyfin/glacier-androidtv/total?style=flat&amp;label=downloads" alt="Total downloads"></a>
  <a href="https://github.com/Glacier-Jellyfin/glacier-androidtv/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/Glacier-Jellyfin/glacier-androidtv/ci.yml?branch=main&amp;style=flat&amp;label=CI" alt="CI status"></a>
  <a href="https://scorecard.dev/viewer/?uri=github.com/Glacier-Jellyfin/glacier-androidtv"><img src="https://img.shields.io/ossf-scorecard/github.com/Glacier-Jellyfin/glacier-androidtv?style=flat&amp;label=openssf%20scorecard" alt="OpenSSF Scorecard"></a>
  <a href="https://www.bestpractices.dev/projects/16652"><img src="https://img.shields.io/cii/level/16652?style=flat&amp;label=openssf%20best%20practices" alt="OpenSSF Best Practices"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/Glacier-Jellyfin/glacier-androidtv?style=flat" alt="License"></a>
</p>

<p align="center">
  <img src="website/assets/screenshots/demo.gif" width="800" alt="Highlights of Glacier on a TV">
</p>

> [!NOTE]
> Glacier is young and still growing. If something does not work, please
> [report it](https://github.com/Glacier-Jellyfin/glacier-androidtv/issues).

## What Glacier does differently

<p align="center">
  <img src="website/assets/screenshots/player.jpg" width="32%" alt="The player with Skip intro">
  <img src="website/assets/screenshots/trickplay.jpg" width="32%" alt="Seeking with a preview picture">
  <img src="website/assets/screenshots/picker.jpg" width="32%" alt="Who's watching with three profiles">
</p>

**For the system under the TV.** TrueHD and DTS-HD reach your receiver exactly
as they are on the disc, even where Android only passes the DTS core or gives
up on TrueHD. If nothing gets through, the server converts to E-AC-3 and
surround stays.

**For the TV itself.** Dolby Vision plays even from UHD Blu-rays with profile
7, converted on the device. The TV switches to the frame rate of the film, and
Settings › System › Check playback tests with real files what your TV can
play.

**For everyone on the sofa.** Everyone gets a profile with their own picture,
colour and progress. The kids profile only shows what suits their age, and the
PIN goes in with the D-pad. The settings come along to a new TV.

## What the remote does

<p align="center">
  <img src="website/assets/screenshots/library.jpg" width="49%" alt="The movie library with the A–Z rail">
  <img src="website/assets/screenshots/detail.jpg" width="49%" alt="A title's page with audio tracks">
</p>

- OK skips the intro, and recaps skip themselves. Credits can skip too
- Seeking shows preview pictures, so you land on the right scene. Chapters are
  one press away
- The next episode is ready before the credits end, with a countdown that can
  shrink out of the way
- The A–Z rail jumps straight to the letter. Large libraries open fast, and
  sorts by year, date added or artist show a heading over each group
- A title's page names every audio and subtitle track in full, with region,
  flag and marks for forced, commentary, audio description or sing-along
- Several versions of a title, such as 2160p and 1080p or a director's cut,
  with the right one picked for your TV and your pick kept per movie or show

## The shelf next to it

<p align="center">
  <img src="website/assets/screenshots/music.jpg" width="32%" alt="The music player in full screen with large lyrics">
  <img src="website/assets/screenshots/photos.jpg" width="32%" alt="The photo gallery">
  <img src="website/assets/screenshots/livetv.jpg" width="32%" alt="Live TV with the channel list and a preview">
</p>

- **Music** that keeps playing while you browse: a mini player, a full screen
  with large lyrics, queue, playlists, instant mix and music videos mixed in
  with the songs
- **Photos and home videos** by folder, as cards or a gallery, full screen with
  a slideshow on OK
- **Live TV** with a running preview on every channel, a programme guide,
  recordings of programmes and series, pause and rewind, and radio stations
  that play like music

## The small print

- Search also finds titles missing from your library and requests them
  through Seerr (needs the Jellyfin Enhanced plugin on the server)
- Multiple servers and profiles, server discovery and Quick Connect. A start
  profile can open by itself
- Parental controls with age limits and PINs for profiles, titles and
  settings, stored only on the device
- Local and YouTube trailers with subtitles, and theme songs on detail pages
- A Favorites page with everything you marked with the heart, in the rows you
  pick
- Audio and subtitle preferences synced with your Jellyfin account
- Android TV home screen: "Watch next" and three optional channels (not on
  Fire TV)
- English, German and Spanish user interface, accent colours and a compact layout
- When playback fails, the message names the error and shows a QR code to
  download the log for a bug report. Settings › System also sends a cleaned
  log to your server
- Built-in updates for the GitHub version, with a Stable and a Beta channel

## Requirements

| | |
|---|---|
| Device | Android TV, Google TV or Fire TV running Android 9 (API 28) or newer |
| Server | Jellyfin 12.0 or newer |

## Installation

Glacier is free and needs no account.

### On your TV in two minutes

<img src="docs/badges/code.svg" width="300" alt="Downloader code 2423112">

1. Install the free [Downloader](https://www.aftvnews.com/downloader/) app from
   the app store on your TV.
2. Open it, enter the code **2423112** and allow the install.
3. Done. The code always loads the newest version, and from then on Glacier
   keeps itself up to date.

### From a store

<a href="https://www.amazon.com/dp/B0HPL2YVVQ"><img src="docs/badges/amazon.svg" height="56" alt="Get it from the Amazon Appstore for Fire TV"></a>
<a href="#become-a-tester-on-google-play"><img src="docs/badges/play.svg" height="56" alt="Google Play: become a tester"></a>

The Amazon Appstore has Glacier for Fire TV, and the store keeps it up to
date like any other app. Google Play is in closed testing.

### Become a tester on Google Play

> [!TIP]
> Google publishes apps from new personal developer accounts only after a
> closed test with at least 12 testers over 14 days. If you have an Android TV
> or Google TV, you can be one of them:
>
> 1. Join the [tester group](https://groups.google.com/g/glacier-jellyfin-client-testers)
>    with the Google account you use on your TV.
> 2. [Opt in to the test](https://play.google.com/apps/testing/io.github.glacier_jellyfin.androidtv)
>    with the same account.
> 3. Open the Play Store on your TV and install Glacier. It can take a few
>    minutes until it shows up.

### As an APK

<a href="https://github.com/Glacier-Jellyfin/glacier-androidtv/releases/latest/download/glacier-androidtv.apk"><img src="docs/badges/github.svg" height="56" alt="Download the APK from GitHub"></a>

Download
[`glacier-androidtv.apk`](https://github.com/Glacier-Jellyfin/glacier-androidtv/releases/latest/download/glacier-androidtv.apk)
and sideload it, for example with `adb install glacier-androidtv.apk`. All
versions are on the [releases page](https://github.com/Glacier-Jellyfin/glacier-androidtv/releases).

<details>
<summary><b>Updates</b></summary>

Installed from GitHub, Glacier keeps itself up to date. It checks for new versions
at every start and installs them from GitHub releases. Android asks once for
permission to install updates from Glacier. You can pick the Stable or Beta
channel in Settings › System.

</details>

<details>
<summary><b>Checking a download</b></summary>

Every release is built on GitHub from the source code in this repository and
carries a signed build provenance attestation. With the
[GitHub CLI](https://cli.github.com/) you can check that your APK came from
here:

```sh
gh attestation verify glacier-androidtv.apk --repo Glacier-Jellyfin/glacier-androidtv
```

</details>

## Building

Requirements: JDK 17 or newer and the Android SDK (Android Studio installs it).

```sh
./gradlew assembleDebug        # debug APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit tests
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the module layout and
[docs/RELEASING.md](docs/RELEASING.md) for versioning and releases.

## How I build Glacier

Glacier is a hobby project. I work as a software developer, and next to my
job I use Glacier to find out how to build software together with AI.

I want to be open about what that means. Almost all code, tests and
documentation are written with
[Claude Code](https://claude.com/claude-code), Anthropic's coding assistant,
working together with me. Every commit it worked on says so with a
`Co-Authored-By: Claude` line, and today that is nearly every commit.

I decide what Glacier does and how it looks and feels. I review the changes
and test them on my own TVs and the Android TV emulator before they ship. On
top of that, every change goes through lint, unit tests and security scans in
CI, and releases are built only by GitHub from the public source code.

Glacier itself has no AI features and sends nothing to AI services. AI makes
mistakes, and so do I. If something looks wrong, please
[tell me](https://github.com/Glacier-Jellyfin/glacier-androidtv/issues).

## Contributing

Bug reports, ideas, pull requests and translations are welcome. Please read
[CONTRIBUTING.md](CONTRIBUTING.md) first.

## Support

Glacier is built in spare time and stays free. If it makes your evenings a
little better, a coffee on Ko-fi keeps it going.

<a href="https://ko-fi.com/jeansy91"><img src="docs/badges/kofi.svg" height="56" alt="Buy me a coffee on Ko-fi"></a>

## License

Glacier is licensed under the [GNU General Public License v3.0 or later](LICENSE).
Third-party parts (fonts, flags, the Media3 decoder module and others) keep their own
licences, listed in [REUSE.toml](REUSE.toml) with the texts in [LICENSES/](LICENSES/).

Glacier is an independent project and is not affiliated with the Jellyfin project.
Amazon, Amazon Appstore and the Amazon logo are trademarks of Amazon.com, Inc. or
its affiliates. Google Play and the Google Play logo are trademarks of Google LLC.
