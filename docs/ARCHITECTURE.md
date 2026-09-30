# Architecture

Glacier is a single-activity Jetpack Compose for TV app, split into a few
Gradle modules with one-way dependencies.

```
app ──► core:designsystem
 │  ──► core:updater
 │  ──► core:player ──► Media3
 │            └──────► core:ffmpeg
 └────► core:data ──► core:jellyfin ──► Jellyfin Kotlin SDK
```

| Module | Responsibility |
|---|---|
| `app` | Entry point, navigation, screens. Each feature is a package: `setup`, `profiles`, `home`, `library`, `detail`, `search`, `player`, `trailer`, `music`, `settings`, `update`. |
| `core:designsystem` | Glacier tokens (colours, accents, shapes) and the Compose theme, plus focus-aware components shared by all screens. |
| `core:jellyfin` | Server discovery, authentication, API access and the device profile sent to the server. |
| `core:data` | Repositories, local settings, and syncing the preferences Jellyfin stores per user. |
| `core:player` | Media3 ExoPlayer setup, media segments, trickplay, track selection. |
| `core:ffmpeg` | Vendored Media3 FFmpeg audio decoder extension (see [core/ffmpeg/README.md](../core/ffmpeg/README.md)). Only linked into the app when the prebuilt `.so` files are present. |
| `core:updater` | Self-update from GitHub releases. |

## Key decisions

### Playback

Media3 ExoPlayer, using the device's hardware decoders. Glacier sends the
server an accurate device profile built from the device's codec capabilities,
so the server decides correctly between direct play and transcoding. Formats
the device cannot handle (for example styled ASS or image subtitles, depending
on the "burn in subtitles" setting) are transcoded or burned in by the server.

The FFmpeg audio decoder extension (DTS, TrueHD without a receiver, and more)
is not published on Maven; `core:ffmpeg` vendors Media3's `decoder_ffmpeg`
source and CI builds the native libraries with the NDK. A debug or release
build without them still works, just without the extra codecs.

### Settings storage

- Stored on the Jellyfin server, per user: preferred audio and subtitle
  language, subtitle mode, "play default audio track", "remember audio/subtitle
  selection".
- Stored on the device, per profile: everything else, including appearance,
  home screen, playback and parental-control settings, and all PINs. There is
  no default PIN; it is set when a PIN feature is first enabled.

### Parental controls

Age ratings are enforced by the server (`maxOfficialRating` on library
queries), since the policy already caps what a query can request. Titles
without a rating are detected client-side and hidden or locked the same way,
because the server does not distinguish "unrated" from "no filter applied"
once a policy exists. A PIN unlocks either a single title or, for a
collection, everything in it, for the rest of the profile's session.

### Server compatibility

Jellyfin 12.0 or newer. Older servers are shown greyed out and can be
connected to only after an explicit confirmation.

### Self-update

See [RELEASING.md](RELEASING.md#how-the-app-updates-itself).
