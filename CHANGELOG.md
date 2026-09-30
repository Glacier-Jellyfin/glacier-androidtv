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

## [0.1.0-beta.1] - 2026-09-30

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
