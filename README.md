<p align="center">
  <img src="website/assets/mark.svg" width="96" alt="Glacier logo">
</p>

<h1 align="center">Glacier</h1>

<p align="center">
  A Jellyfin client for Android TV, Google TV and Fire TV.
</p>

> [!NOTE]
> Glacier is in early development. There is no usable release yet.

## Features (planned for 1.0)

- Movies, shows and music from your Jellyfin server
- Multiple servers and users, server discovery, Quick Connect
- Profile and parental-control PINs, stored only on the device
- Media segments: skip intros, recaps, previews and credits
- Trickplay previews while seeking, chapters, "next episode"
- Audio and subtitle preferences synced with your Jellyfin account
- English and German user interface
- Built-in updates from GitHub releases, with a Stable and a Beta channel

## Requirements

| | |
|---|---|
| Device | Android TV, Google TV or Fire TV running Android 9 (API 28) or newer |
| Server | Jellyfin 12.0 or newer |

## Installation

Glacier is distributed through [GitHub releases](https://github.com/Glacier-Jellyfin/glacier-androidtv/releases)
only. Download `glacier-androidtv-<version>.apk` and sideload it, for example
with [Downloader](https://www.aftvnews.com/downloader/). Once installed, Glacier
keeps itself up to date; Android asks once for permission to install updates
from Glacier.

## Building

Requirements: JDK 17 or newer and the Android SDK (Android Studio installs it).

```sh
./gradlew assembleDebug        # debug APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit tests
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the module layout and
[docs/RELEASING.md](docs/RELEASING.md) for versioning and releases.

## Contributing

Contributions are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) first.

## License

Glacier is licensed under the [GNU General Public License v3.0 or later](LICENSE).

Glacier is an independent project and is not affiliated with the Jellyfin project.
