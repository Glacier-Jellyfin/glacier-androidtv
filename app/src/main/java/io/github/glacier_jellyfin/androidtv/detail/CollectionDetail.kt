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
import androidx.compose.runtime.remember
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
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.ui.ActionButton
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import io.github.glacier_jellyfin.androidtv.ui.DetailBackdrop
import io.github.glacier_jellyfin.androidtv.ui.FactBadge
import io.github.glacier_jellyfin.androidtv.ui.GridCard
import io.github.glacier_jellyfin.androidtv.ui.MediaRow
import io.github.glacier_jellyfin.androidtv.ui.PageEdge
import io.github.glacier_jellyfin.androidtv.ui.ProgressBar
import io.github.glacier_jellyfin.androidtv.ui.ratingText
import io.github.glacier_jellyfin.androidtv.ui.rememberRowPivotSpec
import io.github.glacier_jellyfin.androidtv.ui.runtimeText
import io.github.glacier_jellyfin.androidtv.ui.showsLock

private const val COLLECTION_BACKDROP = 740
private const val TITLE_CLAMP = 22

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionDetail(state: DetailState, details: ItemDetails, viewModel: DetailViewModel) {
    val collection = details.item
    val items = state.collectionItems
    val listState = rememberLazyListState()
    val playFocus = remember { FocusRequester() }
    val accent = LocalAccent.current.main

    Box(Modifier.fillMaxSize()) {
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
                                Text(
                                    stringResource(R.string.library_movies) + " · " + pluralStringResource(R.plurals.count_titles, items.size, items.size),
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
                            CollectionFacts(collection, items)
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
                            val seen = items.count { it.played }
                            Column(Modifier.width(520.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                ProgressBar(if (items.isEmpty()) 0f else seen / items.size.toFloat(), Modifier.height(6.dp))
                                Text(pluralStringResource(R.plurals.collection_seen, items.size, seen, items.size), style = GlacierText.body(17), color = GlacierColors.Mist)
                            }
                            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                val next = viewModel.collectionNext()
                                val title = next?.title?.let { if (it.length > TITLE_CLAMP) it.take(TITLE_CLAMP - 1).trimEnd() + "…" else it }.orEmpty()
                                ActionButton(
                                    onClick = { viewModel.play() },
                                    label = stringResource(if (next?.progress != null) R.string.collection_resume else R.string.collection_play, title),
                                    icon = GlacierIcons.Play,
                                    primary = true,
                                    modifier = Modifier.focusRequester(playFocus),
                                )
                                ActionButton(
                                    onClick = viewModel::toggleFavorite,
                                    icon = if (collection.isFavorite) GlacierIcons.HeartFilled else GlacierIcons.Heart,
                                    on = collection.isFavorite,
                                    contentDescription = stringResource(if (collection.isFavorite) R.string.action_unfavorite else R.string.action_favorite),
                                )
                                val allSeen = items.isNotEmpty() && seen == items.size
                                ActionButton(
                                    onClick = viewModel::togglePlayed,
                                    icon = if (allSeen) GlacierIcons.SeenFilled else GlacierIcons.Seen,
                                    iconMark = if (allSeen) GlacierIcons.SeenMark else null,
                                    on = allSeen,
                                    contentDescription = stringResource(if (allSeen) R.string.collection_unmark_all else R.string.collection_mark_all),
                                )
                            }
                        }
                        PosterStack(items.take(3))
                    }
                    LaunchedEffect(collection.id) {
                        withFrameNanos { }
                        runCatching { playFocus.requestFocus() }
                    }
                }
                if (items.isNotEmpty()) {
                    item(key = "movies") {
                        MediaRow(
                            title = stringResource(R.string.collection_movies),
                            subtitle = stringResource(R.string.collection_chronological),
                            bottomPadding = 110,
                            modifier = Modifier.padding(top = 46.dp),
                        ) {
                            items(items, key = { it.id }) { item -> CollectionCard(item, onClick = { viewModel.openItem(item) }) }
                        }
                    }
                }
            }
        }
    }
}

/** Average rating, year span, total runtime, genres, and a favourite tag. */
@Composable
private fun CollectionFacts(collection: MediaItem, items: List<MediaItem>) {
    val accent = LocalAccent.current.main
    val ratings = items.mapNotNull { it.communityRating }
    val years = items.mapNotNull { it.year }
    val minutes = items.sumOf { it.runtimeMinutes ?: 0 }
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

/** Movie of a collection: poster with its watched state, title and "year · runtime". */
@Composable
private fun CollectionCard(item: MediaItem, onClick: () -> Unit) {
    GridCard(
        imageUrl = item.posterUrl,
        title = item.title,
        caption = listOfNotNull(item.year?.toString(), item.runtimeMinutes?.takeIf { it > 0 }?.let { runtimeText(it) }).joinToString(" · "),
        onClick = onClick,
        watched = item.played,
        locked = item.showsLock(),
    )
}
