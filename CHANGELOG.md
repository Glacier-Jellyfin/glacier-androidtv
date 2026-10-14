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

### New
- Two Live TV rows on the home screen: On now shows what runs on your channels, TV channels lists them by name and logo. Favorites come first, OK tunes in. Turn them on or off and move them in Settings › Home
- Record Live TV. Press OK on a programme in the guide that has not started yet to record it, or the whole series. Hold OK on any programme for all options, also for what runs now (#1)
- Programmes the server records get a red dot in the guide, two dots when the whole series is recorded
- A new Recordings view on the Live TV page lists what you recorded, the series you follow and what is planned. OK plays a recording, holding OK lets you delete it. OK on a planned recording or a series lets you cancel it
- While you watch a channel, Right or the guide key shows what comes next on it. OK on a later programme records it (#1)
- Pause and rewind Live TV. A pause goes on where you stopped, and you can wind back to the moment you tuned in. The channel banner now has one bar for the programme and what you can wind back. OK, Play/Pause, Menu or the spool keys open it: Left and Right wind back and forth, Down reaches the buttons for Play/Pause, 10 seconds back, 30 seconds ahead and Back to live
- The channel list plays the focused channel without sound in its preview. When you open that channel, it goes on playing without loading again, and back in the list it goes on as the preview (#1)
- Radio. Radio stations of Live TV have their own view on the Live TV page and play like music. They go on while you browse the app, show up in the mini player and switch with Up and Down, next and previous or the channel keys. Pausing frees the tuner
- Jellyfin calls radio stations TV channels. A channel that plays with sound and no picture moves to Radio by itself. You can also move channels between Channels and Radio by hand
- Channels and radio stations have a heart in their card, on the Live TV page and in the radio player
- A small options mark shows on the focused element when holding OK opens more options. It appears on channels, radio stations, songs, the queue, recordings and programmes in the guide
- The music player has an Info button like the video player. It shows the song and what comes next, what this device plays and, like for videos, what the server sends and why it converts
- Songs stored without loss, like FLAC or ALAC, show a Lossless mark in the music player

### Improved
- Holding OK in the guide now opens the programme's options. Marking the channel as a favorite is one of them
- After switching channels in the player, Back lands on the channel you watched last, in the channel list and in the guide (#1)
- Back from the player also lands on the channel you watched last when you watched the same channel last time
- Favorites on the Live TV page is now a toggle on the right, in the channel list and in the guide. Channels started from your favorites switch only among your favorites. If you type the number of another channel, the list shows all channels again when you go back
- Movies and shows filter by Unwatched and Favorites with toggles on the right, next to the sort. They work together and with All and Collections, like the heart in music
- Unwatched keeps movies grouped into collections. A collection shows as long as one of its movies is unwatched
- Back in the Live TV channel list, guide, radio or recordings first goes up to the views at the top. Pressed again, it leaves the page
- Right closes the channel list in the Live TV player, like Left closes the programmes
- OK in the Live TV player now shows the controls with Play/Pause. The channel list opens with Left
- While programme data loads, a loader shows instead of "No guide data". Loaders sit in the middle of the space they fill, also in the library
- Back while connecting to a server stops it and stays on the server list
- With many entries in the navigation, it moves aside for the mini player instead of running into it
- New music screensaver. Your queue runs across the screen as a row of covers with the current song in the middle. The row slides on when the song changes, and the picture sways slowly instead of jumping. Title, artist, a progress line and the time of day come with it
- The music and radio player show the time of day as big as the video player does
- The channel panel on the Live TV page always shows what comes next. A long description gets shorter for it
- When the channel panel knows what runs on a channel, its card in the list shows it too
- When a channel does not play, the player says so over the picture without buttons and shows the channel's logo when it has one. Up, Down, the channel list and the programmes keep working, and switching away and back tries again
- Collections in the movies library show how many of their movies you have not watched yet, and a check mark once you have seen them all
- The music player and the radio no longer show format and playback badges at the top. They moved to the info panel

### Fixed
- Play in the spotlight on a show now plays its next episode instead of failing
- Loading and error messages in the players and the trailer view stay readable on bright pictures
- A movie or episode marked as watched shows as watched right away in the library and on the page of its collection or show

## [0.5.0-beta.1] - 2026-10-11

### New
- Live TV, as a first beta: when your server has Live TV set up, a Live TV page joins the top navigation. It lists your channels with what runs on each now, and shows the focused channel's programme and what comes next (#1)
- Watch a channel full screen. Up and Down or the channel keys switch channels, OK or Left opens the channel list, Right or Info shows what is on. Type a channel number to jump to it, and the "last channel" key takes you back
- Hold OK on a channel to make it a favorite, and show only your favorite channels with the Favorites chip
- A programme guide shows three hours of every channel at a time. Left and Right move from programme to programme, Up and Down change the channel at the same time. OK watches what is on now

### Known issues
- Recordings are not there yet. They follow in a later beta

## [0.4.0] - 2026-10-10

### New
- A new Favorites page in the top navigation shows everything you marked with the heart: movies, shows, episodes, albums, artists and songs (#1)
- You can choose which libraries get a card in "My media" and in which order. Open it with the button on the "My media" row in Settings › Home
- You can arrange the top navigation in Settings › Appearance: hide entries and change their order. Search, settings and your profile always stay
- The Favorites page shows the rows you pick, in your order. Open them with the button on its entry in Settings › Appearance
- Your favorite movies and shows have their own row on the home screen (#1)
- You can hide the rows of the home screen and change their order in Settings › Home. Each library has its own "New in" row
- Libraries on the home screen are named after their kind in the app language, e.g. "Movies" or "Shows", when the server has only one of that kind
- Music has a Songs tab. Pick a song to play it with the songs after it
- A heart button in every music tab shows only your favorites. It stays on when you switch tabs

### Improved
- You can sort artists by name or by when they were added
- Albums and songs can be sorted by artist, songs also by album. Each music tab keeps its own sort
- Cards show the value you sort by, like the release year, runtime or rating

### Fixed
- After picking a sort in a library, the focus stays on the sort button instead of jumping to the top navigation
- Playing an artist works when their albums have no album artist set. Glacier now plays the songs tagged with the artist
- The message after failed playback now says what to do next

## [0.3.3] - 2026-10-10

### Improved
- The card for the next episode stays readable on bright scenes. It now has a darker background

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
