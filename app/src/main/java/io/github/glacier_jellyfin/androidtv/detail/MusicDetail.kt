package io.github.glacier_jellyfin.androidtv.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemDetails
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.MusicTrack
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierClickable
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.player.formatTime
import io.github.glacier_jellyfin.androidtv.ui.ActionButton
import io.github.glacier_jellyfin.androidtv.ui.CardShape
import io.github.glacier_jellyfin.androidtv.ui.FactsRow
import io.github.glacier_jellyfin.androidtv.ui.GridCard
import io.github.glacier_jellyfin.androidtv.ui.MediaRow
import io.github.glacier_jellyfin.androidtv.ui.PageEdge
import io.github.glacier_jellyfin.androidtv.ui.audioFormatText
import io.github.glacier_jellyfin.androidtv.ui.rememberRowPivotSpec
import io.github.glacier_jellyfin.androidtv.ui.runtimeText

private const val MUSIC_BACKDROP = 760

/** Album, artist and playlist pages (design: `det.isAlbum`, `isArtist`, `isPlaylist`). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MusicDetail(state: DetailState, details: ItemDetails, viewModel: DetailViewModel) {
    val item = details.item
    val listState = rememberLazyListState()
    val playFocus = remember { FocusRequester() }
    var initialFocusDone by rememberSaveable(item.id) { mutableStateOf(false) }
    val tracks = state.musicTracks

    Box(Modifier.fillMaxSize()) {
        ScrollingBackdrop(item.backdropUrl ?: item.posterUrl, MUSIC_BACKDROP, listState)
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberRowPivotSpec(listState, HEADER_REGION)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                item(key = "header") {
                    Column(
                        Modifier.padding(start = PageEdge.dp, end = PageEdge.dp, top = 130.dp),
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
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            ActionButton(
                                onClick = { viewModel.playMusic() },
                                label = stringResource(
                                    when (item.kind) {
                                        ItemKind.Artist -> R.string.music_play_discography
                                        ItemKind.Playlist -> R.string.music_play_all
                                        else -> R.string.hero_play
                                    },
                                ),
                                icon = GlacierIcons.Play,
                                primary = true,
                                modifier = Modifier.focusRequester(playFocus),
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
                            ActionButton(
                                onClick = viewModel::toggleShuffle,
                                icon = GlacierIcons.Shuffle,
                                on = state.shuffle,
                                contentDescription = stringResource(if (state.shuffle) R.string.action_shuffle_off else R.string.action_shuffle),
                            )
                        }
                    }
                    LaunchedEffect(item.id) {
                        if (!initialFocusDone) {
                            withFrameNanos { }
                            initialFocusDone = runCatching { playFocus.requestFocus() }.isSuccess
                        }
                    }
                }
                if (tracks.isNotEmpty()) {
                    item(key = "tracks-title") {
                        Text(
                            stringResource(R.string.music_tracks),
                            style = GlacierText.display(28),
                            color = GlacierColors.Ice,
                            modifier = Modifier.padding(start = PageEdge.dp, top = 34.dp, bottom = 20.dp),
                        )
                    }
                    // Two columns, filled row by row as in the design's grid.
                    val playlist = item.kind == ItemKind.Playlist
                    items(tracks.chunked(2), key = { pair -> "tracks-" + pair.first().id }) { pair ->
                        Row(
                            Modifier.padding(start = PageEdge.dp, end = PageEdge.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(30.dp),
                        ) {
                            pair.forEach { track ->
                                val number = if (playlist) tracks.indexOf(track) + 1 else track.number
                                TrackRow(track, number, withArtist = playlist, onClick = { viewModel.playMusic(track) }, modifier = Modifier.weight(1f))
                            }
                            if (pair.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                    item(key = "tracks-end") { Box(Modifier.padding(bottom = if (state.similar.isEmpty()) 98.dp else 20.dp)) }
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
    }
}

/** A song in the two-column list: number, title (with artist in playlists), length. */
@Composable
private fun TrackRow(track: MusicTrack, number: Int?, withArtist: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val accent = LocalAccent.current.main
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    GlacierClickable(onClick = onClick, shape = shape, modifier = modifier) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clip(shape)
                .background(if (focused) GlacierColors.GlassFill2 else GlacierColors.GlassFill)
                .padding(horizontal = 24.dp, vertical = if (withArtist) 9.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(number?.let { "%02d".format(it) }.orEmpty(), style = GlacierText.mono(17), color = GlacierColors.Mist)
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = GlacierText.body(19, FontWeight.SemiBold),
                    color = if (focused) accent else GlacierColors.Ice,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (withArtist && track.artist != null) {
                    Text(track.artist.orEmpty(), style = GlacierText.body(15), color = GlacierColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(formatTime(track.durationMs), style = GlacierText.mono(17), color = GlacierColors.Mist)
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
                    tracks.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.count_titles, it, it) },
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
