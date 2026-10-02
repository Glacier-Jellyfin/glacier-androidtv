# Changelog

All notable changes to Glacier are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
the scheme described in [docs/RELEASING.md](docs/RELEASING.md).

Each version section is published verbatim as the GitHub release notes and
shown in the app's update dialog. Use these subsections, in this order, and
omit empty ones:

- `### New`
- `### Improved`
- `### Fixed`
- `### Known issues`

Reference issues or pull requests at the end of a line as `(#123)`; the app
renders the reference separately.

## [Unreleased]

### Fixed
- The first update no longer stops on the installing screen after allowing
  Glacier to install apps: Glacier now asks for that permission itself and
  carries on with the installation once you come back

## [0.1.0-beta.5] - 2026-10-02

### New
- Music keeps playing while you browse Glacier or leave it; a mini player at
  the top right shows the song and opens the full player. Music paused for ten
  minutes ends on its own
- The remote's media keys control the music from anywhere in the app
- Videos and trailers pause the music, and theme songs stay silent while it
  plays
- Hold OK (or press the menu key) on a song for its options: play it next, add
  it to the queue, or add it to a playlist, also a new one; on playlist pages
  songs can be taken out again. Album, artist and playlist pages have the same
  options for all their songs
- In the player, hold OK on a song of the queue to play it next or remove it
- Music videos: a library of their own when the server has one, a row on the
  artist page, and the video player plays them one after another
- Playlists of videos play in the video player, one video after the other;
  mixed playlists show their videos next to the songs
- Instant mix: songs like a song, album, artist or playlist, from its options
- A heart in the music player marks the song that plays as a favorite
- The home screen shows the albums played last and your favorite songs
- While music plays and the remote rests for three minutes, a now-playing
  screen with the cover takes over; any key brings the app back
- Titles above the age limit show a lock on their card while they open with
  the PIN; it goes away once the title is unlocked
- Glacier keeps a log of errors and crashes. Settings › System › Diagnostics
  sends it to your Jellyfin server, where the admin finds it in the dashboard
  under Logs, ready to attach to a bug report. Server addresses, server and
  user names, passwords and access tokens are removed first. After a crash, the next start points you there
- Diagnostics can also show a QR code to download the log on a phone or
  computer on the same network, and turn on detailed logging (playback events
  and navigation) for hard-to-find problems; it turns itself off after 24 hours
- The info sheet in the player has three tabs. The title tab adds the tagline,
  director, cast and studio, the chapter you are in and the next episode.
  Technical shows video, audio, subtitles and the TV in tiles: hardware or
  software decoding, passthrough to the receiver, whether the TV's refresh
  rate fits the film and which HDR formats it takes, plus buffer and network
  speed. Server compares the file with what reaches the device, says why the
  server transcodes and how fast, and whether it uses hardware for it
- When the server has only one profile, Glacier opens it right away at start.
  Settings › System › Start can open the profile used last or a fixed one
  instead, or always ask. A profile with a PIN shows the profiles with it
  selected
- The Android TV home screen shows what you are watching in "Watch next", and
  Glacier offers three channels there: continue watching, recently added and
  new movies and shows. The channels are off until you add them in the TV's
  channel settings. They follow the profile used last and leave out titles
  above its age limit; picking a title opens its page in Glacier, after the
  profile's PIN if it has one. Fire TV does not support either

### Improved
- Coming back to an album or playlist puts the focus on the song you left
- Shuffle and lyrics in the music player are remembered per profile
- The German interface calls the queue "Warteschlange", apart from playlists
- The navigation bar only offers the kinds of library the server has on every
  screen, not just on the home screen
- Glacier looks for updates every time it starts, not just once a day
- The update dialog puts "Beta" and its number on a line of their own

### Fixed
- A dot on the settings gear in the navigation bar now shows that an update
  is waiting, and the dot on System stays while it downloads or after a failed
  attempt
- Picking a profile with another interface language sometimes stayed on
  "Who's watching?" instead of opening the home screen

## [0.1.0-beta.4] - 2026-10-01

### Improved
- The launcher tile shows the Glacier logo with its name

## [0.1.0-beta.3] - 2026-10-01

### New
- Info button in the player with details about the title and technical
  information on the running stream
- The spotlight on the home screen can mix several sources and moves on by
  itself while its play button has the focus
- More subtitle fonts, weights, colours and backgrounds
- The home screen asks before Back quits Glacier
- A show's theme song keeps playing while you move between the show and its
  episodes, and fades out when you leave

### Improved
- Player controls with more contrast; OK pauses while the controls are hidden
- Subtitles sit lower on the screen when nothing covers the picture
- Readable audio and subtitle chips on detail pages
- YouTube trailer controls hide sooner
- The A–Z rail lines up with the first row of titles, and picking a letter
  scrolls the library while the focus stays on the rail
- The library heading only counts titles; the filter chips no longer jump
  while a filter loads
- Settings tell information apart from buttons, open every category at the
  top and move the focus straight into the first card
- Season 0 is named Specials
- Collection pages no longer number their movies

### Fixed
- Watched state updates at once, also when moving on to the next episode
- The subtitle button only appears when there are subtitles
- Rows no longer shift while the focus passes through them
- Left from a settings card returns to the open category
- Round count badges on posters with a single digit

## [0.1.0-beta.2] - 2026-09-30

### New
- Sign in with a Jellyfin account or Quick Connect, choose a public user, and
  switch between saved servers and profiles, with an optional PIN per profile
- Home screen with continue watching, next up and freshly added titles
- Browse movies, shows and music with sorting, filtering, genres and
  collections, and jump around large libraries with an A–Z rail
- Search across the whole library
- Detail pages for movies, series and episodes with cast, similar titles and
  collections
- Video playback with subtitle, audio and chapter selection, trickplay
  scrubbing, media segment skipping (intro, recap, credits) and up next
- Extended audio codec support (DTS, TrueHD and more) for more titles to play
  back directly instead of being transcoded
- Local and YouTube trailers, played from the detail page
- Music library with album, artist and playlist pages, and a dedicated music
  player with lyrics and shuffle
- Settings for playback, audio, subtitles, appearance, home screen, account
  and system, including interface language, theme songs, accent colours and
  card density
- Parental controls: age ratings and PIN locks per profile
- Built-in updater: checks GitHub Releases for Stable or Beta builds and
  installs them without leaving the app

### Known issues
- Some 4K Dolby Vision titles with TrueHD audio fail to play when transcoded
