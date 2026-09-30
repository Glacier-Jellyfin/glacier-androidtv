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
