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
| `app` | Entry point, navigation, screens. Each feature is a package: `setup`, `profiles`, `parental` (PIN and age lock), `home`, `activity`, `library`, `detail`, `search`, `seerr`, `player`, `trailer`, `music`, `channels`, `settings`, `diagnostics`, `update`. `navigation` holds the nav host and top bar; `ui` holds the few app-level building blocks that need app strings or data types (cards, toasts, media text formatting). |
| `core:designsystem` | Glacier tokens (colours, accents, shapes) and the Compose theme, plus the focus-aware components shared by all screens: action button, toggle chip, modal sheet, options panel, overlay card, marquee text, row and card scrolling, and key blocking. |
| `core:jellyfin` | Server discovery, authentication, API access and the device profile sent to the server. |
| `core:data` | Repositories, local settings, and syncing the preferences Jellyfin stores per user. |
| `core:player` | Media3 ExoPlayer setup, media segments, trickplay, track selection. |
| `core:ffmpeg` | Vendored Media3 FFmpeg audio decoder extension (see [core/ffmpeg/README.md](../core/ffmpeg/README.md)). Only linked into the app when the prebuilt `.so` files are present. |
| `core:updater` | Self-update from GitHub releases. |
| `core:log` | Logging to logcat and a small log file on the device, with tokens and passwords removed. Used by the diagnostics upload. |

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

- Stored on the device, for all profiles: the start profile and the "Place
  names" level (in the encrypted account store).

### Place names

Photos with GPS coordinates show a place name from an offline GeoNames pack
(`core:data`, package `geodata`). The pack of places from 15,000 inhabitants is
in the APK (`assets/geodata/places-15000.pack`, the gzipped pack under another
name, because the build would unpack a `.gz` asset) and is the default. A finer
level (minimum inhabitants, or all places) is downloaded as `places-<level>.bin.gz`
from a GitHub release of this repo. Either way the app checks the pinned SHA-256
(table in `GeoPacks.kt`), unpacks the pack to `filesDir/geodata` and memory-maps
it. Lookups are a nearest-neighbour search over a 1 degree grid, within 50 km.
The format is described in the KDoc of `PlacePack`. `scripts/geodata` builds
the packs.

### Parental controls

Age ratings are enforced by the server (`maxOfficialRating` on library
queries), since the policy already caps what a query can request. Titles
without a rating are detected client-side and hidden or locked the same way,
because the server does not distinguish "unrated" from "no filter applied"
once a policy exists. A PIN unlocks either a single title or, for a
collection, everything in it, for the rest of the profile's session.

### Seerr

Search can also show titles from Seerr, the request manager for Jellyfin.
Glacier never talks to Seerr directly. It goes through the proxy of the
Jellyfin Enhanced server plugin (`/JellyfinEnhanced/jellyseerr/*`) with the
normal Jellyfin token, and the plugin maps the Jellyfin user to the Seerr
user. This also works for accounts signed in with Quick Connect, and no Seerr
key is stored on the TV. The plugin API is not public, so the client ignores
unknown fields and hides Seerr results quietly when anything fails.

### Activity

The home row "What others are watching" and the Activity page read the
activity feed of the Jellyfin Enhanced server plugin
(`/JellyfinEnhanced/activity`) with the normal Jellyfin token. It needs
plugin version 12.3.0.0 or newer. The feed fails soft: a 404 means the Activity
Feed is switched off, and any other failure just leaves the row and page out.
Users the admin hid only show when "Hide reviews from hidden users" is off, and
with "ActivityFeedOwnOnlyForNonAdmins" on, non-admins only see their own
entries, so others do not appear. The entries carry item ids only: they are
hydrated in one Items call and filtered by age locally. The result is cached for 30 seconds
and asked for once for the home row and the page (up to 200 entries, the
server maximum). The plugin keeps at most 500 entries for all users together and
drops the oldest, so the page and a person's profile show recent activity only.

`ActivityRepository` does not know the plugin: it asks an `ActivitySource`
(bound in `DataModule`, today `JellyfinEnhancedActivitySource`, which holds the
path, the DTOs and the 401/404 mapping) and keeps the availability memory, the
cache, the hydration and the age filter. A companion plugin would replace only
the source. The repository also publishes the last feed as `latest`.

`ActivityWatchers` (app) turns that feed into `Watchers`: for each movie,
episode and video the people who watched it, and for each show the people who
watched any of its episodes, newest first. The profile itself, the people
hidden in Settings › Activity and favorites are left out. MainActivity
provides it as `LocalWatchers` (empty while "Avatars on cards" is off) and the
base cards take a `watchers` list, which the item-aware call sites fill with
`MediaItem.watchedBy()`. Music, photo and Live TV cards get none. The detail page
shows a quiet line with the avatars and a sentence (`watchersSummary`), and
the player's info panel gets a last tab "Activity" with one card per person
(`personActivities`). Both follow the setting "Activity on the detail page".

The Activity page is stacked rows like the Favorites page: "Watched" and
"Favorited" with one 16:9 card per title (`watchedTitles`, `favoritedTitles`;
episodes of a show fold into a show card when one person watched two or more)
and "People", round portraits that open the person's page
(`ActivityPersonRoute`). That page splits one person's entries into "Watching
now", "Watched" and "Favorites" (`personProfile`) from the cached feed. The
grouping lives in `core:data` (`ActivityGroups`) and is unit-tested.

### Server compatibility

Jellyfin 12.0 or newer. Older servers are shown greyed out and can be
connected to only after an explicit confirmation.

Glacier follows the current Jellyfin release and, like Jellyfin itself,
expects servers to be kept up to date. The Jellyfin Kotlin SDK is updated
with each server release; older SDK lines get no fixes, so Glacier does not
support older server versions in parallel. When a new Jellyfin version
requires it, the minimum version above is raised and the change is noted in
the changelog. Users then have to update their server to keep using new
Glacier releases.

### Self-update

See [RELEASING.md](RELEASING.md#how-the-app-updates-itself).
