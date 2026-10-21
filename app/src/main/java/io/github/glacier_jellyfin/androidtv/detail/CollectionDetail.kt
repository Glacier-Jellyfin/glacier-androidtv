package io.github.glacier_jellyfin.androidtv.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow as DropShadow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemDetails
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.ui.ActionButton
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import io.github.glacier_jellyfin.androidtv.ui.CardShape
import io.github.glacier_jellyfin.androidtv.ui.ContinueCard
import io.github.glacier_jellyfin.androidtv.ui.DetailBackdrop
import io.github.glacier_jellyfin.androidtv.ui.FactBadge
import io.github.glacier_jellyfin.androidtv.ui.GridCard
import io.github.glacier_jellyfin.androidtv.ui.MediaRow
import io.github.glacier_jellyfin.androidtv.ui.PageEdge
import io.github.glacier_jellyfin.androidtv.ui.ProgressBar
import io.github.glacier_jellyfin.androidtv.ui.ratingText
import io.github.glacier_jellyfin.androidtv.ui.rememberRowPivotSpec
import io.github.glacier_jellyfin.androidtv.ui.runtimeText
import io.github.glacier_jellyfin.androidtv.ui.yearText
import io.github.glacier_jellyfin.androidtv.ui.showsLock
import io.github.glacier_jellyfin.androidtv.ui.ImagePageGround

private const val COLLECTION_BACKDROP = 740
private const val TITLE_CLAMP = 22

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionDetail(state: DetailState, details: ItemDetails, viewModel: DetailViewModel) {
    val collection = details.item
    val items = state.collectionItems
    // The header speaks of what is watched; music, people and the like in a collection only get their own rows.
    val watchable = items.filter { it.kind in WatchableInCollection }
    val others = items.size - watchable.size
    val rows = collectionRows(items, viewModel.collectionMoviesWithShows)
    val next = viewModel.collectionNext()
    // Nothing to watch: the music plays, and without music there is no play button.
    val music = next == null && viewModel.collectionHasMusic()
    val playable = next != null || music
    val listState = rememberLazyListState()
    val playFocus = remember { FocusRequester() }
    // Held outside the list: the header leaves the composition when scrolled away and must not take focus again on its way back.
    val focusedFor = rememberSaveable(collection.id) { mutableStateOf<Boolean?>(null) }
    val accent = LocalAccent.current.main

    Box(Modifier.fillMaxSize().background(ImagePageGround)) {
        DetailBackdrop(
            url = collection.backdropUrl ?: items.firstOrNull()?.backdropUrl,
            height = COLLECTION_BACKDROP,
            modifier = Modifier.graphicsLayer {
                translationY = if (listState.firstVisibleItemIndex == 0) -listState.firstVisibleItemScrollOffset.toFloat() else -size.height
            },
        )
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberRowPivotSpec(listState, 700)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                item(key = "header") {
                    Row(Modifier.padding(start = PageEdge.dp, end = PageEdge.dp, top = 146.dp), horizontalArrangement = Arrangement.spacedBy(60.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(
                                    Modifier
                                        .height(36.dp)
                                        .clip(PillShape)
                                        .background(accent.copy(alpha = 0.16f))
                                        .border(1.dp, accent.copy(alpha = 0.4f), PillShape)
                                        .padding(horizontal = 15.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        stringResource(R.string.collection_chip).uppercase(),
                                        style = GlacierText.body(15, FontWeight.Bold).copy(letterSpacing = 0.09.em),
                                        color = accent,
                                    )
                                }
                                val kindLabel = when {
                                    watchable.isEmpty() -> null
                                    watchable.all { it.kind == ItemKind.Movie } -> stringResource(R.string.library_movies)
                                    watchable.all { it.kind == ItemKind.Series } -> stringResource(R.string.library_shows)
                                    else -> null
                                }
                                val titles = if (watchable.isEmpty()) items.size else watchable.size
                                val more = others.takeIf { watchable.isNotEmpty() && it > 0 }?.let { pluralStringResource(R.plurals.collection_more, it, it) }
                                Text(
                                    listOfNotNull(kindLabel, pluralStringResource(R.plurals.count_titles, titles, titles), more).joinToString(" · "),
                                    style = GlacierText.body(19),
                                    color = GlacierColors.Mist,
                                )
                            }
                            Text(
                                collection.title,
                                style = GlacierText.display(70).copy(lineHeight = 73.sp, shadow = Shadow(Color(0x99000000), blurRadius = 30f)),
                                color = GlacierColors.Ice,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            CollectionFacts(collection, watchable)
                            collection.overview?.let {
                                Text(
                                    it,
                                    style = GlacierText.body(21).copy(lineHeight = 34.sp),
                                    color = GlacierColors.Ice,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 820.dp),
                                )
                            }
                            val seen = watchable.count { it.played }
                            if (watchable.isNotEmpty()) {
                                Column(Modifier.width(520.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    ProgressBar(seen / watchable.size.toFloat(), Modifier.height(6.dp))
                                    Text(pluralStringResource(R.plurals.collection_seen, watchable.size, seen, watchable.size), style = GlacierText.body(17), color = GlacierColors.Mist)
                                }
                            }
                            Row(Modifier.padding(top = 10.dp).upTo(LocalMiniPlayerFocus.current), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (playable) {
                                    val title = next?.title?.let { if (it.length > TITLE_CLAMP) it.take(TITLE_CLAMP - 1).trimEnd() + "…" else it }.orEmpty()
                                    ActionButton(
                                        onClick = { viewModel.play() },
                                        label = when {
                                            music -> stringResource(R.string.collection_play_music)
                                            next?.progress != null -> stringResource(R.string.collection_resume, title)
                                            else -> stringResource(R.string.collection_play, title)
                                        },
                                        icon = GlacierIcons.Play,
                                        primary = true,
                                        modifier = Modifier.focusRequester(playFocus),
                                    )
                                }
                                ActionButton(
                                    onClick = viewModel::toggleFavorite,
                                    icon = if (collection.isFavorite) GlacierIcons.HeartFilled else GlacierIcons.Heart,
                                    on = collection.isFavorite,
                                    contentDescription = stringResource(if (collection.isFavorite) R.string.action_unfavorite else R.string.action_favorite),
                                    modifier = if (playable) Modifier else Modifier.focusRequester(playFocus),
                                )
                                if (watchable.isNotEmpty()) {
                                    val allSeen = seen == watchable.size
                                    ActionButton(
                                        onClick = viewModel::togglePlayed,
                                        icon = if (allSeen) GlacierIcons.SeenFilled else GlacierIcons.Seen,
                                        iconMark = if (allSeen) GlacierIcons.SeenMark else null,
                                        on = allSeen,
                                        contentDescription = stringResource(if (allSeen) R.string.collection_unmark_all else R.string.collection_mark_all),
                                    )
                                }
                            }
                        }
                        // Posters fan out well; covers and portraits only when there is nothing else.
                        PosterStack(items.filter { it.kind in PosterKinds && it.posterUrl != null }.ifEmpty { items }.take(3))
                    }
                    // The play button only comes with the titles: focus moves on to it once they are loaded.
                    LaunchedEffect(collection.id, playable) {
                        if (focusedFor.value == playable) return@LaunchedEffect
                        withFrameNanos { }
                        runCatching { playFocus.requestFocus() }
                        focusedFor.value = playable
                    }
                }
                rows.forEachIndexed { index, (row, rowItems) ->
                    item(key = row.name) {
                        MediaRow(
                            title = stringResource(rowTitle(row, single = rows.size == 1)),
                            subtitle = stringResource(R.string.collection_chronological).takeIf { row !in UndatedRows },
                            bottomPadding = if (index == rows.lastIndex) 110 else null,
                            modifier = if (index == 0) Modifier.padding(top = 46.dp) else Modifier,
                        ) {
                            items(rowItems, key = { it.id }) { item -> CollectionCard(item, viewModel) }
                        }
                    }
                }
            }
        }
    }
}

/** Kinds with a 2:3 poster. */
private val PosterKinds = setOf(ItemKind.Movie, ItemKind.Series, ItemKind.Season)

/** Rows the server's year order says nothing about. */
private val UndatedRows = setOf(CollectionRow.Artists, CollectionRow.People)

/** A collection of one kind keeps the long title ("Movies in this collection"); rows of a mixed one are short. */
private fun rowTitle(row: CollectionRow, single: Boolean): Int = when (row) {
    CollectionRow.Movies -> if (single) R.string.collection_movies else R.string.collection_row_movies
    CollectionRow.Shows -> if (single) R.string.collection_shows else R.string.collection_row_shows
    CollectionRow.Videos -> if (single) R.string.collection_titles else R.string.collection_row_videos
    CollectionRow.Seasons -> R.string.collection_row_seasons
    CollectionRow.Episodes -> R.string.collection_row_episodes
    CollectionRow.MusicVideos -> R.string.collection_row_music_videos
    CollectionRow.Music -> R.string.collection_row_music
    CollectionRow.Artists -> R.string.collection_row_artists
    CollectionRow.People -> R.string.collection_row_people
    CollectionRow.More -> R.string.collection_row_more
}

/** Average rating, year span, total runtime, genres, and a favourite tag. */
@Composable
private fun CollectionFacts(collection: MediaItem, items: List<MediaItem>) {
    val accent = LocalAccent.current.main
    val ratings = items.mapNotNull { it.communityRating }
    val years = items.mapNotNull { it.year }
    // A show's runtime is one episode's: only movies add up to a total.
    val minutes = items.filter { it.kind != ItemKind.Series }.sumOf { it.runtimeMinutes ?: 0 }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        if (ratings.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(GlacierIcons.Star, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.collection_average, ratingText(ratings.average().toFloat())), style = GlacierText.body(20, FontWeight.Bold), color = accent)
            }
        }
        val span = when {
            years.isEmpty() -> null
            years.min() == years.max() -> years.min().toString()
            else -> "${years.min()}–${years.max()}"
        }
        val genres = items.flatMap { it.genres }.distinct().take(3).joinToString(" · ").ifEmpty { null }
        val facts = listOfNotNull(span, minutes.takeIf { it > 0 }?.let { runtimeText(it) }, genres)
        if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), style = GlacierText.body(20), color = GlacierColors.Mist)
        if (collection.isFavorite) FactBadge(stringResource(R.string.favorite_tag), accent.copy(alpha = 0.45f), 16, accent)
    }
}

/** Up to three posters, fanned out as in the design (±2.5°). */
@Composable
private fun PosterStack(items: List<MediaItem>) {
    val shape = RoundedCornerShape(GlacierShapes.RadiusLg)
    Box(Modifier.size(560.dp, 500.dp)) {
        // Drawn back to front so the first poster lies on top.
        items.indices.reversed().forEach { i ->
            Box(
                Modifier
                    .offset(x = (i * 140).dp, y = (18 + i * 14).dp)
                    .size(280.dp, 420.dp)
                    .rotate((i - 1) * 2.5f)
                    .dropShadow(shape, DropShadow(radius = 40.dp, spread = (-14).dp, color = Color.Black.copy(alpha = 0.55f), offset = DpOffset(0.dp, 18.dp)))
                    .clip(shape)
                    .border(1.dp, GlacierColors.GlassBorder2, shape),
            ) {
                Artwork(items[i].posterUrl, Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * One title of a collection, in the card its kind gets elsewhere: posters for movies, shows and seasons
 * (shows and seasons count their unwatched episodes), stills for episodes and music videos,
 * square covers for music and round portraits for people.
 */
@Composable
private fun CollectionCard(item: MediaItem, viewModel: DetailViewModel) {
    val locked = item.showsLock()
    when (item.kind) {
        ItemKind.Episode, ItemKind.MusicVideo -> {
            val season = item.seasonNumber
            val episode = item.episodeNumber
            val number = if (item.kind == ItemKind.Episode && season != null && episode != null) stringResource(R.string.episode_short, season, episode) else null
            ContinueCard(
                title = if (item.kind == ItemKind.Episode) item.parentTitle ?: item.title else item.title,
                subtitle = if (item.kind == ItemKind.Episode) {
                    listOfNotNull(number, item.title).joinToString(" · ")
                } else {
                    listOfNotNull(item.parentTitle, item.year?.toString()).joinToString(" · ")
                },
                imageUrl = item.thumbUrl ?: item.posterUrl,
                progress = item.progress,
                onClick = { viewModel.openItem(item) },
                locked = locked,
            )
        }
        ItemKind.Album, ItemKind.Song, ItemKind.Playlist -> GridCard(
            imageUrl = item.posterUrl,
            title = item.title,
            caption = item.parentTitle,
            onClick = { if (item.kind == ItemKind.Song) viewModel.playCollectionSong(item) else viewModel.openItem(item) },
            shape = CardShape.Square,
            locked = locked,
        )
        ItemKind.Artist, ItemKind.Person -> GridCard(
            imageUrl = item.posterUrl,
            title = item.title,
            caption = null,
            onClick = { if (item.kind == ItemKind.Person) viewModel.openPerson(item) else viewModel.openItem(item) },
            shape = CardShape.Round,
        )
        else -> {
            val counted = item.kind == ItemKind.Series || item.kind == ItemKind.Season
            GridCard(
                imageUrl = item.posterUrl,
                title = item.title,
                caption = when (item.kind) {
                    ItemKind.Series -> yearText(item)
                    ItemKind.Season -> item.parentTitle
                    else -> listOfNotNull(item.year?.toString(), item.runtimeMinutes?.takeIf { it > 0 }?.let { runtimeText(it) }).joinToString(" · ")
                },
                onClick = { if (item.kind == ItemKind.Season) viewModel.openSeason(item) else viewModel.openItem(item) },
                watched = item.played && item.kind in WatchableInCollection,
                count = item.unwatchedCount.takeIf { counted && !item.played },
                locked = locked,
            )
        }
    }
}
