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

[CHANGELOG.de.md](CHANGELOG.de.md) holds the same sections in German. The
release notes carry them in a collapsed block, and the app shows them when its
language is German.

## [Unreleased]

### New
- Home video and photo libraries get their own entry, Photos & videos, in the navigation bar. Browse them by folder, with the folders apart from the videos and photos and the path above the title. The other videos of a folder follow one after another
- Photos and videos open full screen, a video playing right there. Left and right browse the folder with an animated transition, OK on a photo starts or stops a slideshow, and OK on a video opens the player where it is. A line along the bottom shows how far a video has played or a photo's time in the slideshow. Settings › Playback sets how long the slideshow shows each photo
- A folder shows its videos and photos as cards, or as a gallery: the focused picture fills the screen behind the title and folders, with a row of small pictures along the bottom to click through. Moving into the row brings the whole picture to the front above the row and slides the navigation bar away, and a video plays in it. Chips at the left show all, only videos or only photos, and menus at the right pick the view and sort by the date taken or by name. Back first goes up to the chips. Sorted by date, each month gets a heading among the cards, and sorted by name an A–Z rail jumps to a letter. The full-screen view browses the videos too. Settings › Appearance shapes the cards 16:9 or 4:3

### Improved
- The navigation bar leaves room for notices at the top right. When its entries need more room, the ones between search and settings scroll, and search, settings and the profile always stay in view
- The player no longer calls music videos and recordings "Movies"
- Back from a "More like this" title returns to the library or search you started in, with the last title in focus

### Fixed
- Right from the profile in the navigation bar no longer jumps down to the A–Z rail of a library
- Left from a library's A–Z rail lands on the title the letter jumped to, not on the title focused before
- Dolby Vision titles in a profile the device cannot decode are converted by the server instead of failing to play (#21)
- When the device cannot decode the picture or sound of a file, the player says so instead of asking to check the server (#21)

## [0.7.0] - 2026-10-29

### New
- Each profile's settings are saved on its Jellyfin server, once per device. A profile new on a device (after installing again, switching to the Play Store or Amazon Appstore version, or on another TV) is offered the settings of a known device once. Settings › Account › Saved settings can take them over later. It can also delete copies no longer needed, such as an old install’s copy
- Settings › Account › Remove from this device forgets sign-in, PIN, parental control and settings of a profile on this TV, optionally with its settings saved on the server. The Jellyfin account stays

### Improved
- The skip button fades away in one second instead of two and a half, and its border fades along with it instead of staying visible last

## [0.6.3] - 2026-10-28

### Improved
- The update dialog on the home screen can be turned off in Settings › System. A new version still shows there and as a dot on the settings gear

## [0.6.2] - 2026-10-27

### Improved
- The skip button for intros, recaps and credits fades out after a while when the controls are hidden, so it no longer covers the picture. Press Up to bring it back; it also shows with the controls. Set the time in Settings › Playback › Segments, or turn it off
- Pausing a video puts the focus on the timeline, so Left and Right scrub straight away. OK plays on
- The ring on the skip button and the bar on the Up next card empty smoothly instead of in steps
- On the profile screen the focus ring and the name of a profile show that profile's accent colour
- Back works the same on every page with the navigation bar. It goes up one step at a time, from the titles to the tabs or the menu on the left, then to the navigation bar. Back in the navigation bar goes to the home screen. On the home screen it asks whether to close the app, as before

### Fixed
- "Use the audio track of the last title" and "Use the subtitles of the last title" now work per show. Before, the tracks picked in one show carried over to every other show and film. A film keeps the tracks last picked in it. The tracks remembered so far start over once
- After a long time in the background the app could no longer load anything, and Refresh did not help either. It now starts over with the profile and loads normally
- The clock and the end time in the player keep moving while a video is paused. Before, they stood still until it played on
- After you come back from the player or mark a title as watched, Continue watching starts at the left again, where the title just watched now sits

## [0.6.1] - 2026-10-22

### New
- Live TV can list the channels by channel number or by channel name, up or down. Pick the order with the sort button next to Favorites. Picking it again turns the direction round. The guide and switching channels in the player follow the same order

### Improved
- The release notes of an update in Settings › System are split into New, Improved and Fixed, like in the update dialog
- A profile without a picture shows its own accent colour behind its initial on the profile screen

### Fixed
- Live TV lists channels without a number after the numbered ones, not before them
- Live TV no longer keeps a tuner busy on the server after a channel started. This happened with a bitrate limit or when Glacier picked the main audio track of a channel
- Live TV no longer keeps a tuner busy after you moved on from a channel that was slow to start. Once the server has opened it, Glacier gives it back

## [0.6.0] - 2026-10-21

### New
- Pick the version of a movie or episode that has several files, such as 2160p and 1080p, or cuts like a Director's Cut. A chip before the audio track shows the version and opens the list. Glacier starts with the largest file of the main cut that your device plays without converting and that fits the screen. Your pick stays for the movie, or for all episodes of the show. Length and end time follow the version (#8)
- Album pages have a button with the artist's name that opens the artist's page

### Improved
- Large libraries open fast. The list is kept on the device and refreshed in the background. A new sort shows at once
- The first load of a very large library shows how far it has come, such as "12000 of 70406 songs loaded", and that this only happens the first time
- Sorted by title, the library sorts names itself. Names that start with a symbol or a number come first, then A to Z. Numbers go by value, so 2 comes before 10. The A–Z rail matches the list
- Sorted by date added, year, rating, length, artist or album, the library shows a small heading over each group, such as "2024" or "October 2026". Inside a group the titles go from A to Z. Songs of an album keep their track order
- The A–Z rail jumps straight to the letter, also in very large libraries. The titles before it load when you scroll back up
- Sorted by artist or album, the A–Z rail jumps to the artists or albums
- Moving onto the A–Z rail always lands on the current letter
- The filter and sort buttons stay in the same place on every tab, with or without the A–Z rail
- The loading sign and the text of an empty list sit in the middle of the screen, with or without the A–Z rail
- When a library view cannot load or has nothing to show, the message sits in the middle of the page, with the Retry button below it
- Album and playlist pages list their songs in one column. Up and Down go song by song. Each song shows its length, and its artist where that differs from the album. Playlists also show the album. Albums with more than one disc show a heading per disc
- Album pages show the cover left of the songs. It stays in place while you scroll through the songs
- Playlist pages show the cover of the focused song on the left, with its album, artist and year below
- The Songs tab is a list too, with cover, title, artist, album and length
- Holding OK on a song opens its options. The focused song shows a mark for this at its end, and it grows like a card
- On album, artist and playlist pages, Back from the songs or the rows below first goes up to Play
- With the lyrics on in the music player, Left from the queue goes to the last button and Left from the lyrics to Play/Pause
- Detail pages show the mini player while music plays. Up from the buttons at the top reaches it
- The mini player and messages sit as far from the right edge as from the top
- Forced, hearing impaired, sing-along, commentary and audio description tracks are found by their name in many more languages, such as French, Spanish, Italian, Portuguese, Dutch, the Nordic languages, Polish and Russian. Accents and spellings like Sing-Along or Singalong no longer matter
- The PIN is typed with the D-pad, like on the Fire TV. OK and the four directions type 0–4. Holding OK or pressing the menu key switches to 5–9. Back or rewind deletes. The number keys of the remote work as well. The pressed digit no longer lights up, so nobody watching sees the PIN
- A PIN can be removed in Settings › Account, next to Change. This switches off all PIN locks
- Checking the PIN is much faster on slow TVs. Before, it could take over a second and keys pressed meanwhile were lost

### Fixed
- The Songs tab of a very large music library no longer closes the app while it loads
- The A–Z rail of the Songs tab covers all songs, also in libraries with more than 40,000 songs
- A library that is still loading no longer takes the focus away from a tab you moved to
- A new age limit or "Lock unrated titles" applies to the library lists at once. Before, a list kept on the device could still show the titles from before
- With "Lock unrated titles" on, albums, artists and playlists open again. Music has no age rating, so it is never locked
- Subtitles for signs and songs show as forced, not as sing-along. This also covers German names like "Schild- und Liedtext"
- An audio track named like "ger ac3 6ch" no longer shows as Swiss German
- Back in the music player closes an open options menu or info panel and leaves the player open. Before, newer Android TV versions closed the player as well
- On a title without cast, "More like this" no longer sits right under the buttons

### Known issues
- All versions of a title share one resume point. The server keeps only one per title

## [0.5.0] - 2026-10-16

### New
- Live TV. When your server has Live TV set up, a Live TV page joins the top navigation. It lists your channels with what runs on each now and what comes next. The focused channel plays without sound as a preview (#1)
- Watch a channel full screen. Up and Down or the channel keys switch channels, Left opens the channel list, Right or the guide key shows what comes next. Type a channel number to jump to it, and the "last channel" key takes you back
- Pause and rewind Live TV. A pause goes on where you stopped, and you can wind back to the moment you tuned in. OK, Play/Pause, Menu or the spool keys open the controls
- A programme guide shows three hours of every channel at a time. OK watches what is on now or records a later programme
- Record Live TV. Record a programme or the whole series from the guide or the player. Hold OK on a programme for all options (#1)
- The Recordings view on the Live TV page lists what you recorded, the series you follow and what is planned. Programmes the server records carry a red dot in the guide
- Radio. Radio stations have their own view on the Live TV page and play like music. They go on while you browse the app and show up in the mini player. A channel with sound and no picture moves to Radio by itself, and you can move channels by hand
- Favorite channels and radio stations with the heart, and show only your favorites with the toggle on the right
- Two Live TV rows for the home screen: On now and TV channels. Turn them on or off and move them in Settings › Home
- The music player and the Live TV player have an Info button like the video player. It shows what plays, what this device plays and what the server sends
- Songs stored without loss, like FLAC or ALAC, show a Lossless mark in the music player
- A small options mark shows on the focused element when holding OK opens more options
- Shows have a Collections tab like movies. A collection page plays a show from its next episode
- A collection with mixed content shows one row per kind: movies, shows, seasons, episodes, music videos, music, artists and people. A new setting under Appearance puts movies and shows in one row
- Movies and shows filter by Unwatched and Favorites with toggles next to the sort. They work together and with All and Collections
- Collections and show cards in the library count what you have not watched yet. A check mark shows once you have seen everything
- Four new accent colours: Alpenglow, Dawn, Midnight sun and Twilight. Settings shows all accents in spectrum order
- Forced, hearing impaired, closed caption, commentary, audio description and sing-along tracks carry a mark. Glacier also finds them by the track's name when the file does not flag them

### Improved
- Audio and subtitle tracks read the same as in the Glacier web plugin. Each row shows the language, with the format and the track's name in a quieter second line
- Language variants show their region and flag, such as French (Canada) or Portuguese (Brazil). Chinese shows its script, and Cantonese tracks are named as such
- Audio formats are named in full, such as DTS-HD MA 5.1 or TrueHD Atmos 7.1
- A commentary or audio description track is no longer picked on its own because the file marks it as default. The main track of the same language plays instead
- Back in a library or on the Live TV page first goes up to the tabs at the top. Pressed again, it leaves the page
- New music screensaver. Your queue runs across the screen as a row of covers with the current song in the middle
- The music player shows the time of day as big as the video player does. Format and playback badges moved to the info panel
- With many entries in the navigation, it moves aside for the mini player
- Loaders sit in the middle of the space they fill, also in the library
- Back while connecting to a server stops it and stays on the server list
- Settings › Appearance lists the navigation entries last, after the library options

### Fixed
- A title starts with the audio and subtitle tracks its detail page shows. Before, it could start with other tracks, such as forced subtitles, or with the server's default audio when transcoding
- Subtitles no longer stay empty when a file marks its forced track as the default. Tracks named Forced or Signs now show as forced
- On Fire TV a title no longer fails to start when the TV switches its frame rate. Glacier waits a moment and plays on
- Play in the spotlight on a show now plays its next episode instead of failing
- A movie or episode marked as watched shows as watched right away in the library and on the page of its collection or show
- A collection page counts only what can be watched. Songs, people and the like no longer add to the seen count, the total runtime or the genres
- A season in a collection opens its show on that season
- Loading and error messages in the players and the trailer view stay readable on bright pictures

## [0.5.0-beta.6] - 2026-10-15

### Fixed
- A transcoded title plays the audio track the detail page shows. Before, the server could send the track played last time or its default, such as German instead of Japanese

## [0.5.0-beta.5] - 2026-10-15

### Fixed
- Belgian, Swiss, Austrian, Australian and Argentinian language variants show their flag. In 0.5.0-beta.4 the flag stayed empty
- Audio tracks named "Hörfilmfassung" carry the audio description mark
- A title starts with the audio and subtitle tracks its detail page shows. Before, it could start with other subtitles, such as the forced ones, when the audio came from the last title

## [0.5.0-beta.4] - 2026-10-15

### Improved
- Audio and subtitle tracks read the same as in the Glacier web plugin. Each row shows the language, with the format and the track's name in a quieter second line. Default and external tracks say so there too
- Forced, hearing impaired, closed caption, commentary and audio description tracks carry a mark at the end of the row. Glacier also finds them by the track's name when the file does not flag them. The audio and subtitle buttons on the detail page show the mark as an icon next to the language
- Language variants show their region and flag, such as French (Canada), Portuguese (Brazil) or Dutch (Belgium). Chinese shows its script, traditional or simplified, and Cantonese tracks are named as such. Glacier reads all this from the language tag or the track's name. The usual variant of a language, such as French from France, shows no addition
- Audio formats are named in full, such as DTS-HD MA 5.1 or TrueHD Atmos 7.1
- A commentary or audio description track is no longer picked on its own because the file marks it as default. The main track of the same language plays instead

### Fixed
- The track sheet opens at once on titles with many audio or subtitle tracks. Before, it could take several seconds on a Fire TV

## [0.5.0-beta.3] - 2026-10-15

### New
- Four new accent colours: Alpenglow, Dawn, Midnight sun and Twilight. With the five Glacier accents they cover the whole rainbow. Settings shows them in spectrum order, in one row that scrolls when it does not fit
- The Live TV player has an Info button like the video and music players. It shows the programme and what comes next, what this device plays and what the server sends and why it converts. It sits at the end of the controls, and the Info key on the remote opens it too

### Fixed
- When the server ends a Live TV stream, the channel now starts again by itself. Before, the picture stopped and stayed on screen
- The preview in the channel list also starts again when the server ends it. Opening a channel whose preview had ended no longer loads forever
- Back from the player on a slower device, the channel list keeps playing the channel you watched. Before, it loaded it again
- After you remove the last recording, the focus stays on Recordings instead of jumping to the top bar
- The queue card in the music info panel shows the time left in full

## [0.5.0-beta.2] - 2026-10-15

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
- Subtitles no longer stay empty when a file marks its forced track as the default. If the audio is in another language, the full track of the same language plays instead. Tracks named Forced or Signs now show as forced in the track picker
- The page of an episode or movie shows the audio and subtitles that will play. When the tracks of the last title are taken over, the page shows them too instead of the server's defaults

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
