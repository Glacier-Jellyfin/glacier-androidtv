package io.github.glacier_jellyfin.androidtv.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow as DropShadow
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
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalReduceMotion
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.ui.ActionButton
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import io.github.glacier_jellyfin.androidtv.ui.ProgressBar
import io.github.glacier_jellyfin.androidtv.ui.ageRatingText
import io.github.glacier_jellyfin.androidtv.ui.episodeText
import io.github.glacier_jellyfin.androidtv.ui.qualityText
import io.github.glacier_jellyfin.androidtv.ui.ratingText
import io.github.glacier_jellyfin.androidtv.ui.runtimeText

const val SpotlightHeight = 680

/** The home screen's hero ("Spotlight"): backdrop, title, facts, actions and position dots. */
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
) {
    val item = items.getOrNull(index) ?: return
    // Clipped: the Ken Burns zoom would otherwise spill over the first row.
    Box(modifier.fillMaxWidth().height(SpotlightHeight.dp).clipToBounds()) {
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
                items.indices.forEach { i -> Dot(active = i == index, onClick = { onSelect(i) }) }
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
        Facts(item)
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
            ActionButton(onClick = onPlay, label = playLabel, icon = GlacierIcons.Play, primary = true, modifier = Modifier.focusRequester(playFocus))
            ActionButton(onClick = onInfo, label = stringResource(R.string.hero_more_info))
            ActionButton(
                onClick = onFavorite,
                icon = if (item.isFavorite) GlacierIcons.HeartFilled else GlacierIcons.Heart,
                on = item.isFavorite,
                contentDescription = stringResource(if (item.isFavorite) R.string.action_unfavorite else R.string.action_favorite),
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

/** Rating, age rating, year · runtime · genre, quality badge. */
@Composable
private fun Facts(item: MediaItem) {
    val accent = LocalAccent.current.main
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        item.communityRating?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(GlacierIcons.Star, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Text(ratingText(it), style = GlacierText.body(20, FontWeight.Bold), color = accent)
            }
        }
        ageRatingText(item.officialRating)?.let { Badge(it, GlacierColors.GlassBorder2, 17, GlacierColors.Ice) }
        val runtime = item.runtimeMinutes?.takeIf { it > 0 }?.let { runtimeText(it) }
        val facts = listOfNotNull(item.year?.toString(), runtime, item.genres.firstOrNull())
        if (facts.isNotEmpty()) {
            Text(facts.joinToString("  ·  "), style = GlacierText.body(20), color = GlacierColors.Mist)
        }
        qualityText(item.quality)?.let { Badge(it, GlacierColors.GlassBorder, 15, GlacierColors.Mist) }
    }
}

@Composable
private fun Badge(text: String, border: Color, size: Int, color: Color) {
    val shape = RoundedCornerShape(GlacierShapes.RadiusSm)
    Box(Modifier.border(1.dp, border, shape).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, style = GlacierText.body(size).copy(letterSpacing = 0.06.em), color = color)
    }
}

/** Position dot; the active one is a wide accent pill. Focusable to jump to that title. */
@Composable
private fun Dot(active: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current.main
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val width by animateDpAsState(if (active) 34.dp else 12.dp, label = "dot")
    Box(
        Modifier
            .height(26.dp)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .then(if (focused) Modifier.dropShadow(PillShape, DropShadow(radius = 0.dp, spread = 4.dp, color = accent.copy(alpha = 0.35f))) else Modifier)
                .width(width)
                .height(12.dp)
                .clip(PillShape)
                .background(if (active) accent else Color(0x4DE8F4F7)),
        )
    }
}

/** The design's `gKen`: a slow 22 s zoom and drift, alternating. Off with "reduce motion". */
@Composable
private fun KenBurns(content: @Composable () -> Unit) {
    if (LocalReduceMotion.current) {
        content()
        return
    }
    val transition = rememberInfiniteTransition(label = "ken")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(22_000), RepeatMode.Reverse), label = "kenT")
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val scale = 1.02f + 0.07f * t
                scaleX = scale
                scaleY = scale
                translationX = -0.012f * size.width * t
                translationY = -0.01f * size.height * t
            },
    ) { content() }
}
