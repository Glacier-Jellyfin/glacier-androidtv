# Architecture

Glacier is a single-activity Jetpack Compose for TV app, split into a few
Gradle modules with one-way dependencies.

```
app ──► core:designsystem
 │  ──► core:updater
 │  ──► core:player ──► Media3
 └────► core:data ──► core:jellyfin ──► Jellyfin Kotlin SDK
```

| Module | Responsibility |
|---|---|
| `app` | Entry point, navigation, screens. Each feature is a package: `setup`, `profiles`, `home`, `library`, `detail`, `search`, `player`, `music`, `settings`. |
| `core:designsystem` | Glacier tokens (colours, accents, shapes) and the Compose theme, plus focus-aware components shared by all screens. |
| `core:jellyfin` | Server discovery, authentication, API access and the device profile sent to the server. |
| `core:data` | Repositories, local settings, and syncing the preferences Jellyfin stores per user. |
| `core:player` | Media3 ExoPlayer setup, media segments, trickplay, track selection. |
| `core:updater` | Self-update from GitHub releases. |

## Key decisions

### Playback

Media3 ExoPlayer, using the device's hardware decoders. Glacier sends the
server an accurate device profile built from the device's codec capabilities,
so the server decides correctly between direct play and transcoding. Formats
the device cannot handle (for example styled ASS or image subtitles, depending
on the "burn in subtitles" setting) are transcoded or burned in by the server.

The FFmpeg audio decoder extension (DTS, TrueHD without a receiver) is not
published on Maven and will be built from Media3 sources with the NDK.

### Settings storage

- Stored on the Jellyfin server, per user: preferred audio and subtitle
  language, subtitle mode, "play default audio track", "remember audio/subtitle
  selection".
- Stored on the device, per profile: everything else, including all PINs.
  There is no default PIN; it is set when a PIN feature is first enabled.

### Server compatibility

Jellyfin 12.0 or newer. Older servers are shown greyed out and can be
connected to only after an explicit confirmation.

### Self-update

See [RELEASING.md](RELEASING.md#how-the-app-updates-itself).
