package io.github.glacier_jellyfin.androidtv.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.ui.ActionButton
import io.github.glacier_jellyfin.androidtv.ui.FactsRow
import io.github.glacier_jellyfin.androidtv.ui.KenBurns
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import io.github.glacier_jellyfin.androidtv.ui.ProgressBar
import io.github.glacier_jellyfin.androidtv.ui.episodeText
import io.github.glacier_jellyfin.androidtv.ui.runtimeText
const val SpotlightHeight = 680

/**
 * The home screen's hero ("Spotlight"): backdrop, title, facts, actions and position dots.
 * Left on "Play" and Right on the favorite button move to the previous and next title;
 * coming into it from elsewhere always lands on "Play".
 */
@Composable
fun Spotlight(
    items: List<MediaItem>,
    index: Int,
    onSelect: (Int) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onInfo: (MediaItem) -> Unit,
    onFavorite: (MediaItem) -> Unit,
    playFocus: FocusRequester,
    modifier: Modifier = Modifier,
    /** Time to the next title as 0..1, shown in the active dot; null while it does not move on. */
    progress: () -> Float? = { null },
    /** Lets the screen find a button again (keys "play", "info", "favorite"). */
    buttonModifier: (String) -> Modifier = { Modifier },
) {
    val item = items.getOrNull(index) ?: return
    fun step(by: Int) = Modifier.onPreviewKeyEvent { event ->
        val key = if (by < 0) Key.DirectionLeft else Key.DirectionRight
        if (items.size < 2 || event.key != key) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyDown) onSelect((index + by).mod(items.size))
        true
    }
    // Clipped: the Ken Burns zoom would otherwise spill over the first row.
    Box(
        modifier
            .fillMaxWidth()
            .height(SpotlightHeight.dp)
            .clipToBounds()
            .focusProperties { onEnter = { playFocus.requestFocus() } }
            .focusGroup(),
    ) {
        Crossfade(targetState = item.backdropUrl, animationSpec = tween(600), label = "backdrop") { url ->
            KenBurns { Artwork(url, Modifier.fillMaxSize()) }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(0f to Color(0xF50A1420), 0.38f to Color(0xC20A1420), 0.72f to Color(0x1A0A1420))),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(260.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, GlacierColors.Void))),
        )
        SpotlightInfo(
            item = item,
            onPlay = { onPlay(item) },
            onInfo = { onInfo(item) },
            onFavorite = { onFavorite(item) },
            playFocus = playFocus,
            buttonModifier = { key ->
                when (key) {
                    "play" -> step(-1)
                    "favorite" -> step(1)
                    else -> Modifier
                }.then(buttonModifier(key))
            },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 80.dp, bottom = 74.dp)
                .width(900.dp),
        )
        if (items.size > 1) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 80.dp, bottom = 78.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items.indices.forEach { i -> Dot(active = i == index, progress = progress) }
            }
        }
    }
}

@Composable
private fun SpotlightInfo(
    item: MediaItem,
    onPlay: () -> Unit,
    onInfo: () -> Unit,
    onFavorite: () -> Unit,
    playFocus: FocusRequester,
    buttonModifier: (String) -> Modifier,
    modifier: Modifier,
) {
    val resuming = item.progress != null
    val isEpisode = item.kind == ItemKind.Episode
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (resuming) ResumeChip()
        Text(
            if (isEpisode) item.parentTitle ?: item.title else item.title,
            style = GlacierText.display(76).copy(lineHeight = 78.sp, shadow = Shadow(Color(0x99000000), blurRadius = 30f)),
            color = GlacierColors.Ice,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (isEpisode) {
            val episode = episodeText(item)
            Text(listOfNotNull(episode, item.title).joinToString(" · "), style = GlacierText.body(24, FontWeight.SemiBold), color = GlacierColors.Ice)
        }
        FactsRow(item, listOfNotNull(item.year?.toString(), item.runtimeMinutes?.takeIf { it > 0 }?.let { runtimeText(it) }, item.genres.firstOrNull()))
        item.overview?.let {
            Text(
                it,
                style = GlacierText.body(21).copy(lineHeight = 34.sp),
                color = GlacierColors.Ice,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(820.dp),
            )
        }
        if (resuming) {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                ProgressBar(item.progress ?: 0f, Modifier.height(6.dp))
                item.remainingMinutes?.let {
                    Text(stringResource(R.string.minutes_left, it), style = GlacierText.body(17), color = GlacierColors.Mist)
                }
            }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val season = item.seasonNumber
            val episode = item.episodeNumber
            val playLabel = when {
                !resuming -> stringResource(R.string.hero_play)
                isEpisode && season != null && episode != null -> stringResource(R.string.hero_resume_episode, season, episode)
                else -> stringResource(R.string.hero_resume)
            }
            ActionButton(onClick = onPlay, label = playLabel, icon = GlacierIcons.Play, primary = true, modifier = Modifier.focusRequester(playFocus).then(buttonModifier("play")))
            ActionButton(onClick = onInfo, label = stringResource(R.string.hero_more_info), modifier = buttonModifier("info"))
            ActionButton(
                onClick = onFavorite,
                icon = if (item.isFavorite) GlacierIcons.HeartFilled else GlacierIcons.Heart,
                on = item.isFavorite,
                contentDescription = stringResource(if (item.isFavorite) R.string.action_unfavorite else R.string.action_favorite),
                modifier = buttonModifier("favorite"),
            )
        }
    }
}

@Composable
private fun ResumeChip() {
    val accent = LocalAccent.current.main
    Row(
        Modifier
            .height(38.dp)
            .clip(PillShape)
            .background(accent.copy(alpha = 0.18f))
            .border(1.dp, accent.copy(alpha = 0.4f), PillShape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(GlacierIcons.Play, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
        Text(
            stringResource(R.string.home_continue_watching).uppercase(),
            style = GlacierText.body(15, FontWeight.Bold).copy(letterSpacing = 0.09.em),
            color = accent,
        )
    }
}

/**
 * Position dot; the active one is a wide pill. While the spotlight moves on by
 * itself the accent fills it as the time to the next title runs down.
 */
@Composable
private fun Dot(active: Boolean, progress: () -> Float?) {
    val accent = LocalAccent.current.main
    val width by animateDpAsState(if (active) 34.dp else 12.dp, label = "dot")
    Box(
        Modifier
            .height(26.dp)
            .width(width)
            // Drawn, not composed: the fill changes every frame.
            .drawBehind {
                val bar = Size(size.width, 12.dp.toPx())
                val top = Offset(0f, (size.height - bar.height) / 2)
                val radius = CornerRadius(bar.height / 2)
                val filled = if (active) progress() else null
                val track = when {
                    !active -> Color(0x4DE8F4F7)
                    filled != null -> accent.copy(alpha = 0.3f)
                    else -> accent
                }
                drawRoundRect(track, top, bar, radius)
                if (filled != null && filled > 0f) {
                    // A plain bar cut to the pill: a narrow round rect of its own would bulge out.
                    val pill = Path().apply { addRoundRect(RoundRect(Rect(top, bar), radius)) }
                    clipPath(pill) { drawRect(accent, top, Size(bar.width * filled.coerceIn(0f, 1f), bar.height)) }
                }
            },
    )
}
