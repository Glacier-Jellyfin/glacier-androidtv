package io.github.glacier_jellyfin.androidtv.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.animation.Crossfade
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemDetails
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.MusicTrack
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.music.MusicActionsSheet
import io.github.glacier_jellyfin.androidtv.ui.ActionButton
import io.github.glacier_jellyfin.androidtv.ui.CardShape
import io.github.glacier_jellyfin.androidtv.ui.ContinueCard
import io.github.glacier_jellyfin.androidtv.ui.FactsRow
import io.github.glacier_jellyfin.androidtv.ui.GridCard
import io.github.glacier_jellyfin.androidtv.ui.SONG_ROW_GAP
import io.github.glacier_jellyfin.androidtv.ui.SongRow
import io.github.glacier_jellyfin.androidtv.ui.MediaRow
import io.github.glacier_jellyfin.androidtv.ui.PageEdge
import io.github.glacier_jellyfin.androidtv.ui.audioFormatText
import io.github.glacier_jellyfin.androidtv.ui.rememberRowPivotSpec
import io.github.glacier_jellyfin.androidtv.ui.runtimeText
import io.github.glacier_jellyfin.androidtv.ui.showsLock
import io.github.glacier_jellyfin.androidtv.ui.ImagePageGround
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import coil3.compose.AsyncImage
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.ui.RowPivot
import io.github.glacier_jellyfin.androidtv.ui.SONG_ROW_HEIGHT
import java.util.UUID
import kotlinx.coroutines.launch

private const val MUSIC_BACKDROP = 760

/** The cover left of the songs, and the space between them. */
private const val COVER_SIZE = 400
private const val COVER_GAP = 48

/** Room below a playlist's cover for the focused song's album, artist and year. */
private const val CAPTION_HEIGHT = 90

/** Focus key of the options button (the others are song ids, or null for Play). */
private const val MORE_KEY = "more"

/** Album, artist and playlist pages (design: `det.isAlbum`, `isArtist`, `isPlaylist`). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MusicDetail(state: DetailState, details: ItemDetails, viewModel: DetailViewModel) {
    val item = details.item
    val listState = rememberLazyListState()
    val playFocus = remember { FocusRequester() }
    val moreFocus = remember { FocusRequester() }
    val trackFocus = remember { mutableMapOf<UUID, FocusRequester>() }
    // What had focus, to land there again when the user comes back (from the player, say).
    var focusKey by rememberSaveable(item.id) { mutableStateOf<String?>(null) }
    val tracks = state.musicTracks
    /** A playlist with videos and no songs: Play starts the videos. */
    val videosOnly = item.kind == ItemKind.Playlist && tracks.isEmpty() && state.playlistVideos.isNotEmpty()
    val playlist = item.kind == ItemKind.Playlist
    // Albums and playlists show a cover left of the songs. It stays put while the songs scroll past.
    // A playlist's follows the focused song, with its album below; before that it shows the playlist.
    val focusedTrack = if (playlist) tracks.firstOrNull { it.id.toString() == focusKey } else null
    val coverUrl = when {
        tracks.isEmpty() -> null
        item.kind == ItemKind.Album -> item.posterUrl
        playlist -> focusedTrack?.coverUrl ?: item.posterUrl ?: tracks.first().coverUrl
        else -> null
    }
    val coverHeight = COVER_SIZE + if (playlist) CAPTION_HEIGHT else 0
    val songIndent = if (coverUrl != null) (COVER_SIZE + COVER_GAP).dp else 0.dp
    val discs = tracks.mapNotNull { it.disc }.distinct().size > 1
    // Songs that open a new disc get a heading above them.
    val discStarts = if (discs && !playlist) {
        tracks.indices.filter { tracks[it].disc != null && tracks[it].disc != tracks.getOrNull(it - 1)?.disc }.toSet()
    } else {
        emptySet()
    }
    val sheet by viewModel.musicActions.sheet.collectAsStateWithLifecycle()
    // Also once the options sheet closes, which takes the focus with it. The key is kept from when it
    // opened: on closing, focus falls on Play for a moment before this runs.
    var keyAtSheet by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(item.id, sheet == null) {
        if (sheet != null) {
            keyAtSheet = focusKey
            return@LaunchedEffect
        }
        withFrameNanos { }
        val key = keyAtSheet ?: focusKey
        keyAtSheet = null
        val requester = when (key) {
            null -> playFocus
            MORE_KEY -> moreFocus
            else -> trackFocus.entries.firstOrNull { it.key.toString() == key }?.value ?: playFocus
        }
        runCatching { requester.requestFocus() }
    }

    // Back among the songs and rows below first goes up to Play. Pressed again, it leaves the page.
    var listFocused by remember { mutableStateOf(false) }
    var headerFocused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = listFocused && !headerFocused) {
        scope.launch {
            listState.scrollToItem(0)
            withFrameNanos { }
            runCatching { playFocus.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize().background(ImagePageGround)) {
        ScrollingBackdrop(item.backdropUrl ?: item.posterUrl, MUSIC_BACKDROP, listState)
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberRowPivotSpec(listState, HEADER_REGION)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().onFocusChanged { listFocused = it.hasFocus }) {
                item(key = "header") {
                    Column(
                        Modifier
                            .padding(start = PageEdge.dp, end = PageEdge.dp, top = 130.dp)
                            .onFocusChanged { headerFocused = it.hasFocus },
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        val music = stringResource(R.string.library_music)
                        Crumb(
                            when (item.kind) {
                                ItemKind.Artist -> music + " · " + stringResource(R.string.music_artist)
                                ItemKind.Playlist -> music + " · " + stringResource(R.string.music_playlist)
                                else -> listOfNotNull(music, item.parentTitle).joinToString(" · ")
                            },
                        )
                        Title(item.title, size = 84, maxWidth = 1180)
                        // Age ratings are a film thing: the design leaves them off music.
                        FactsRow(item.copy(officialRating = null), musicFacts(state, item), tech = musicTech(tracks))
                        item.overview?.takeIf { it.isNotBlank() }?.let { Overview(it, maxWidth = 900) }
                        Row(Modifier.padding(top = 10.dp).upTo(LocalMiniPlayerFocus.current), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            ActionButton(
                                // A playlist of videos only plays them in the video player.
                                onClick = { if (videosOnly) viewModel.playVideo(state.playlistVideos.first()) else viewModel.playMusic() },
                                label = stringResource(
                                    when (item.kind) {
                                        ItemKind.Artist -> R.string.music_play_discography
                                        ItemKind.Playlist -> R.string.music_play_all
                                        else -> R.string.hero_play
                                    },
                                ),
                                icon = GlacierIcons.Play,
                                primary = true,
                                modifier = Modifier
                                    .focusRequester(playFocus)
                                    .onFocusChanged { if (it.isFocused) focusKey = null },
                            )
                            ActionButton(
                                onClick = viewModel::toggleFavorite,
                                icon = if (item.isFavorite) GlacierIcons.HeartFilled else GlacierIcons.Heart,
                                on = item.isFavorite,
                                contentDescription = stringResource(if (item.isFavorite) R.string.action_unfavorite else R.string.action_favorite),
                            )
                            if (item.kind == ItemKind.Album) {
                                ActionButton(
                                    onClick = viewModel::togglePlayed,
                                    icon = if (item.played) GlacierIcons.SeenFilled else GlacierIcons.Seen,
                                    iconMark = if (item.played) GlacierIcons.SeenMark else null,
                                    on = item.played,
                                    contentDescription = stringResource(if (item.played) R.string.action_mark_unheard else R.string.action_mark_heard),
                                )
                            }
                            // Shuffle and the song options are about songs.
                            if (!videosOnly) {
                                ActionButton(
                                    onClick = viewModel::toggleShuffle,
                                    icon = GlacierIcons.Shuffle,
                                    on = state.shuffle,
                                    contentDescription = stringResource(if (state.shuffle) R.string.action_shuffle_off else R.string.action_shuffle),
                                )
                                ActionButton(
                                    onClick = viewModel::sourceOptions,
                                    icon = GlacierIcons.More,
                                    contentDescription = stringResource(R.string.music_options),
                                    modifier = Modifier
                                        .focusRequester(moreFocus)
                                        .onFocusChanged { if (it.isFocused) focusKey = MORE_KEY },
                                )
                            }
                        }
                    }
                }
                if (tracks.isNotEmpty()) {
                    item(key = "tracks-title") {
                        Text(
                            stringResource(R.string.music_tracks),
                            style = GlacierText.display(28),
                            color = GlacierColors.Ice,
                            modifier = Modifier.padding(start = PageEdge.dp + songIndent, end = PageEdge.dp, top = 34.dp, bottom = 20.dp),
                        )
                    }
                    // One column: Up and Down go song by song. Holding OK opens a song's options.
                    // Albums name each song's artist only when they differ (a sampler, a guest).
                    val withArtist = playlist || tracks.any { it.artist != null && it.artist != item.parentTitle }
                    tracks.forEachIndexed { index, track ->
                        val disc = track.disc
                        if (index in discStarts && disc != null) {
                            item(key = "disc-$disc-$index") {
                                Text(
                                    stringResource(R.string.music_disc, disc),
                                    style = GlacierText.body(17, FontWeight.SemiBold),
                                    color = GlacierColors.Mist,
                                    modifier = Modifier.padding(start = PageEdge.dp + songIndent + 24.dp, top = if (index == 0) 0.dp else 18.dp, bottom = 10.dp),
                                )
                            }
                        }
                        item(key = "track-" + index + "-" + track.id) {
                            val focus = remember(track.id) { FocusRequester() }.also { trackFocus[track.id] = it }
                            SongRow(
                                title = track.title,
                                durationMs = track.durationMs,
                                number = if (playlist) index + 1 else track.number,
                                artist = if (playlist) track.artist else track.artist?.takeIf { it != item.parentTitle },
                                album = track.album,
                                showArtist = withArtist,
                                // The cover beside the list names the album of the focused song.
                                showAlbum = playlist && coverUrl == null,
                                onClick = { viewModel.playMusic(track) },
                                onOptions = { viewModel.trackOptions(track) },
                                modifier = Modifier
                                    .padding(start = PageEdge.dp + songIndent, end = PageEdge.dp, bottom = SONG_ROW_GAP.dp)
                                    .focusRequester(focus)
                                    .onFocusChanged { if (it.isFocused) focusKey = track.id.toString() },
                            )
                        }
                    }
                    item(key = "tracks-end") {
                        // A short album still leaves room for the whole cover before the next row.
                        val coverRest = if (coverUrl != null) (coverHeight - tracks.size * (SONG_ROW_HEIGHT + SONG_ROW_GAP)).coerceAtLeast(0) else 0
                        Box(Modifier.height(coverRest.dp + if (state.similar.isEmpty()) 98.dp else 20.dp))
                    }
                }
                if (state.artistAlbums.isNotEmpty()) {
                    item(key = "albums") {
                        MediaRow(title = stringResource(R.string.music_albums), bottomPadding = 120, modifier = Modifier.padding(top = 20.dp)) {
                            items(state.artistAlbums, key = { it.id }) { album ->
                                GridCard(
                                    imageUrl = album.posterUrl,
                                    title = album.title,
                                    caption = listOfNotNull(
                                        album.year?.toString(),
                                        album.childCount?.let { pluralStringResource(R.plurals.count_titles, it, it) },
                                    ).joinToString(" · "),
                                    onClick = { viewModel.openItem(album) },
                                    shape = CardShape.Square,
                                )
                            }
                        }
                    }
                }
                // An artist's music videos, a playlist's videos: each plays with the rest following.
                val videos = if (item.kind == ItemKind.Artist) state.artistVideos else state.playlistVideos
                if (videos.isNotEmpty()) {
                    item(key = "videos") {
                        MediaRow(
                            title = stringResource(if (item.kind == ItemKind.Artist) R.string.library_music_videos else R.string.music_videos),
                            bottomPadding = 120,
                            modifier = Modifier.padding(top = if (tracks.isEmpty()) 34.dp else 0.dp),
                        ) {
                            items(videos, key = { it.id }) { video ->
                                ContinueCard(
                                    title = video.title,
                                    subtitle = listOfNotNull(video.parentTitle, video.year?.toString(), video.runtimeMinutes?.takeIf { it > 0 }?.let { runtimeText(it) })
                                        .joinToString(" · "),
                                    imageUrl = video.thumbUrl ?: video.posterUrl,
                                    progress = video.progress,
                                    onClick = { viewModel.playVideo(video) },
                                    locked = video.showsLock(),
                                )
                            }
                        }
                    }
                }
                if (state.similar.isNotEmpty()) {
                    item(key = "similar") {
                        MediaRow(title = stringResource(R.string.detail_similar), bottomPadding = 120) {
                            items(state.similar, key = { it.id }) { similar ->
                                GridCard(
                                    imageUrl = similar.posterUrl,
                                    title = similar.title,
                                    caption = similar.parentTitle,
                                    onClick = { viewModel.openItem(similar) },
                                    shape = CardShape.Square,
                                )
                            }
                        }
                    }
                }
            }
        }
        // Items: header, tracks title, then the songs with their disc headings, then the end spacer.
        if (coverUrl != null) {
            StickyCover(listState, firstIndex = 2, endIndex = 2 + tracks.size + discStarts.size, height = coverHeight) {
                Cover(coverUrl)
                if (playlist) focusedTrack?.let { TrackCaption(it) }
            }
        }
        MusicActionsSheet(viewModel.musicActions)
    }
}

/**
 * The cover beside the songs: it starts level with the first song, stops at [RowPivot]
 * (where a focused song settles) and leaves with the last song. [firstIndex] is the list item
 * that starts the songs, [endIndex] the spacer after them.
 */
@Composable
private fun StickyCover(listState: LazyListState, firstIndex: Int, endIndex: Int, height: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .padding(start = PageEdge.dp)
            .size(COVER_SIZE.dp, height.dp)
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    val info = listState.layoutInfo
                    val visible = info.visibleItemsInfo
                    val first = visible.firstOrNull { it.index == firstIndex }
                    val end = visible.firstOrNull { it.index == endIndex }
                    val firstVisible = visible.firstOrNull()?.index ?: 0
                    val lastVisible = visible.lastOrNull()?.index ?: 0
                    val viewport = info.viewportEndOffset
                    // Where the songs begin and end on screen, beyond either edge when scrolled away.
                    val top = first?.offset ?: if (firstIndex < firstVisible) Int.MIN_VALUE / 2 else viewport
                    val bottom = (end?.offset ?: if (endIndex > lastVisible) Int.MAX_VALUE / 2 else Int.MIN_VALUE / 2) - SONG_ROW_GAP.dp.roundToPx()
                    val y = maxOf(RowPivot.dp.roundToPx(), top)
                        .coerceAtMost(maxOf(top, bottom - placeable.height))
                        .coerceIn(-placeable.height, viewport)
                    placeable.place(0, y)
                }
            },
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

/** A square cover that fades over to the next one. */
@Composable
private fun Cover(url: String?) {
    Crossfade(url, label = "cover", modifier = Modifier.size(COVER_SIZE.dp)) { shown ->
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(GlacierShapes.RadiusLg)).background(GlacierColors.GlassFill)) {
            if (shown != null) AsyncImage(model = shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** The focused song's album, then artist and year, below a playlist's cover. */
@Composable
private fun TrackCaption(track: MusicTrack) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        track.album?.let {
            Text(it, style = GlacierText.body(20, FontWeight.SemiBold), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        listOfNotNull(track.artist, track.year?.toString()).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
            Text(it, style = GlacierText.body(18), color = GlacierColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Album: year · titles and length · genre. Artist: newest year · albums, titles, length · genres. Playlist: titles and length. */
@Composable
private fun musicFacts(state: DetailState, item: MediaItem): List<String> {
    val tracks = state.musicTracks
    return when (item.kind) {
        ItemKind.Artist -> {
            val albums = state.artistAlbums
            val titles = albums.sumOf { it.childCount ?: 0 }
            val minutes = albums.sumOf { it.runtimeMinutes ?: 0 }
            listOfNotNull(
                albums.mapNotNull { it.year }.maxOrNull()?.toString(),
                listOfNotNull(
                    albums.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.count_albums, it, it) },
                    titles.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.count_titles, it, it) },
                    minutes.takeIf { it > 0 }?.let { runtimeText(it) },
                ).joinToString(" · ").ifEmpty { null },
                (item.genres.ifEmpty { albums.flatMap { it.genres } }).distinct().take(2).joinToString(" · ").ifEmpty { null },
            )
        }
        else -> {
            val minutes = (tracks.sumOf { it.durationMs } / 60_000).toInt().takeIf { it > 0 } ?: item.runtimeMinutes
            listOfNotNull(
                item.year?.toString(),
                listOfNotNull(
                    (tracks.size + state.playlistVideos.size).takeIf { it > 0 }?.let { pluralStringResource(R.plurals.count_titles, it, it) },
                    minutes?.takeIf { it > 0 }?.let { runtimeText(it) },
                ).joinToString(" · ").ifEmpty { null },
                item.genres.firstOrNull(),
            )
        }
    }
}

/** The format badge when all songs share a codec; mixed lists get none. */
@Composable
private fun musicTech(tracks: List<MusicTrack>): String? {
    val formats = tracks.mapNotNull { it.format }
    if (formats.isEmpty() || formats.map { it.codec?.lowercase() }.distinct().size > 1) return null
    return audioFormatText(formats.first())
}
