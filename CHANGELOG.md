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

## [0.3.2] - 2026-10-07

### Improved
- A show opens on its first season with unwatched episodes. Specials come only after that
- On an episode page, the resume button no longer repeats the time left. The line above it already shows it

### Fixed
- Shows with many seasons list every season. The season row now scrolls
- A person with several roles appears only once in the cast row

## [0.3.1] - 2026-10-07

### Improved
- Movie and episode pages show when the title would end if you start it now
- Shows list the years they ran, such as 2018 – 2024, or 2000 – today while they are still running
- After playback, a show's page moves to the episode you watched last, or to the next one if you finished it
- The player controls show a larger clock and no status labels. Quality and playback method are in the info panel
- The quality label on a title's page shows only the video format, so it stays short

### Fixed
- When a skip prompt such as "Skip intro" appears while the play button has focus, the prompt now gets the focus

## [0.3.0] - 2026-10-04

### New
- The TV switches to the frame rate of the video, so films play without judder. You can turn this off in Settings › Playback
- Trailers have a subtitles button. Pick a language or turn them off. Trailers start without subtitles, and your choice carries over to the next trailers
- In the trailer player you can move along the progress bar with left and right
- With Seerr, a person's filmography also lists titles that are not in your library yet. Open one to request it
- On a Seerr request page, the cast opens the person's page instead of a search
- After a crash, Glacier opens a page at the next start. Scan its code to save the log, then report the error on GitHub with the log attached

### Improved
- When the server converts surround sound, it sends Dolby Digital or Dolby Digital Plus if your receiver or TV takes it. The sound stays surround over HDMI ARC
- The update dialog lists the changes of every version since yours, not only the newest one
- After "Update now", the download shows its progress right away. Back cancels it
- A trailer still goes full screen quickly when it starts. When you bring the controls back, they stay longer
- Back from a title on a person's page, or from a person on a Seerr page, lands on the card you opened

### Fixed
- Trailers play more smoothly. The background image no longer moves while a trailer plays
- Some converted videos no longer fail to start, e.g. 4K films with TrueHD sound
- Biographies and descriptions no longer show HTML code such as <p> or links

### Security
- The audio decoder for DTS, TrueHD and other formats is based on a newer FFmpeg with current security fixes
- Trailers from Seerr only accept real YouTube video IDs

## [0.2.1] - 2026-10-04

### Improved
- Glacier can be installed with the code 2423112 in the Downloader app on the TV. The code always loads the newest version
- Updates now come as a file with the same name for every version. This keeps download links stable

## [0.2.0] - 2026-10-04

### New
- Search also finds movies and shows that are not in the library yet, through Seerr. This needs the Jellyfin Enhanced plugin with Seerr set up on the server. Library titles come first, Seerr titles follow with their state
- A Seerr title opens its own page with plot, cast and trailer. Movies and single seasons of a show can be requested from there

### Improved
- Pages without a picture show a gradient from the accent colour into black
- The top navigation shows your profile picture when your account has one
- The library is sorted by title from A to Z unless you pick another order
- The music player has a button that adds the playing song to a playlist
- When you pick a playlist, its number of songs shows as a badge on the right
- The music player uses this gradient instead of the blurred cover. The line about the next song is gone, since the queue on the right already shows it

### Fixed
- Holding OK on a song now keeps its options open. On many remotes the menu closed right away and ran its first option
- Back in the music player closes an open options sheet and no longer the player too
- Marking a title watched or unwatched on its page now updates Home and the Android TV home screen right away
- The library keeps its sort order after the app restarts. Movies, shows and music each remember their own

## [0.1.1] - 2026-10-03

### Improved
- The search field looks like the fields of the sign-in screens. OK on it opens the system keyboard

### Fixed
- Settings › Home scrolls down to the spotlight options again while no spotlight content is selected
- On the search page, Up from the keypad reaches the search field and the top navigation again instead of opening the keyboard

## [0.1.0] - 2026-10-02

The first release of Glacier. Thanks to everyone who tried the betas.

### New
- Sign in with a Jellyfin account or Quick Connect, find servers on the network, and switch between saved servers and profiles. When the server has only one profile, Glacier opens it right away; Settings › System › Start can open the last or a fixed profile instead
- Parental controls: age limits and PINs for profiles, titles and settings, stored only on the device
- Home screen with continue watching, next up, recently added, a spotlight and rows for music
- Movies, shows and music with sorting, filters, genres, collections and an A–Z rail for large libraries, plus search across the whole server
- Detail pages with cast, similar titles, collections, local and YouTube trailers, and theme songs
- A player made for watching: skip intros, recaps and credits, trickplay previews while seeking, chapters, and the next episode with a countdown. The info button explains what plays: the title, the stream on the device and what the server does with it
- Extended audio codec support (DTS, TrueHD and more), so more titles play directly instead of being transcoded
- Music keeps playing while you browse, with a mini player, queue, playlists, lyrics, instant mix, favorites, music videos and a now-playing screen; the remote's media keys work everywhere
- Subtitle styles, and audio and subtitle preferences synced with your Jellyfin account
- Android TV home screen: "Watch next" and three optional channels (not on Fire TV)
- English and German interface, accent colours and a compact layout
- Diagnostics send a cleaned error log to your Jellyfin server for bug reports
- Built-in updates from GitHub releases, with a Stable and a Beta channel

### Known issues
- Some 4K Dolby Vision titles with TrueHD audio fail to play when transcoded
