package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierCard
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusFrame

/** Card sizes of the design's two row densities (Settings › Appearance). */
@Immutable
data class CardSizes(
    val continueWidth: Int,
    val continueHeight: Int,
    val posterWidth: Int,
    val posterHeight: Int,
    val rowGap: Int,
    /** Space below a row of cards. */
    val rowBottom: Int,
    val gridColumns: Int,
    val gridGap: Int,
    val episodeWidth: Int,
    val episodeHeight: Int,
    val castSize: Int,
    val castGap: Int,
) {
    companion object {
        val Comfortable = CardSizes(
            continueWidth = 316, continueHeight = 178, posterWidth = 216, posterHeight = 324, rowGap = 26, rowBottom = 46,
            gridColumns = 7, gridGap = 26, episodeWidth = 340, episodeHeight = 192, castSize = 132, castGap = 30,
        )
        val Compact = CardSizes(
            continueWidth = 276, continueHeight = 155, posterWidth = 188, posterHeight = 282, rowGap = 20, rowBottom = 34,
            gridColumns = 8, gridGap = 22, episodeWidth = 296, episodeHeight = 167, castSize = 114, castGap = 24,
        )
    }
}

val LocalCardSizes = staticCompositionLocalOf { CardSizes.Comfortable }

/** "My media" tiles keep their size in both densities. */
object CardSize {
    const val LIBRARY_WIDTH = 340
    const val LIBRARY_HEIGHT = 172
}

/**
 * Server artwork over a quiet accent-tinted fill, so missing images still look intentional.
 * A failed load is tried once more: Jellyfin can fail a resize it is asked for several
 * times at once (the same cover down a music queue) until the first one is cached.
 */
@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Box(modifier.background(Brush.linearGradient(listOf(accent.deep.copy(alpha = 0.45f), GlacierColors.Deep)))) {
        if (url != null) {
            var attempt by remember(url) { mutableIntStateOf(0) }
            var failed by remember(url) { mutableStateOf(false) }
            LaunchedEffect(failed) {
                if (failed && attempt < ARTWORK_RETRIES) {
                    delay(ARTWORK_RETRY_DELAY_MS)
                    attempt++
                    failed = false
                }
            }
            key(attempt) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { failed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private const val ARTWORK_RETRIES = 1
private const val ARTWORK_RETRY_DELAY_MS = 800L

/** "Continue watching": 16:9 still, progress bar, play button on focus, title and time left. */
@Composable
fun ContinueCard(title: String, subtitle: String, imageUrl: String?, progress: Float?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current.main
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    val sizes = LocalCardSizes.current
    GlacierCard(onClick = onClick, modifier = modifier.width(sizes.continueWidth.dp)) { focused ->
        Box(
            Modifier
                .size(sizes.continueWidth.dp, sizes.continueHeight.dp)
                .focusFrame(focused, shape)
                .clip(shape),
        ) {
            Artwork(imageUrl, Modifier.fillMaxSize())
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(70.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD905090F)))),
            )
            if (progress != null) {
                ProgressBar(
                    progress = progress,
                    track = Color(0x42E8F4F7),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = 20.dp)
                        .height(5.dp),
                )
            }
            val playAlpha by animateFloatAsState(if (focused) 1f else 0f, label = "play")
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .scale(0.8f + 0.2f * playAlpha)
                    .alpha(playAlpha)
                    .clip(PillShape)
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(GlacierIcons.Play, contentDescription = null, tint = GlacierColors.Void, modifier = Modifier.size(24.dp))
            }
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title,
                style = GlacierText.body(19, FontWeight.SemiBold),
                color = if (focused) accent else GlacierColors.Ice,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, style = GlacierText.body(16), color = GlacierColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Poster (2:3) or album cover (square). Posters carry their title in the
 * artwork, so only the caption line is shown below, as in the design.
 */
@Composable
fun PosterCard(
    imageUrl: String?,
    caption: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    square: Boolean = false,
    title: String? = null,
    badge: Int? = null,
) {
    val accent = LocalAccent.current.main
    val shape: Shape = RoundedCornerShape(if (square) GlacierShapes.RadiusMd else GlacierShapes.RadiusLg)
    val sizes = LocalCardSizes.current
    val width = sizes.posterWidth
    val height = if (square) sizes.posterWidth else sizes.posterHeight
    GlacierCard(onClick = onClick, modifier = modifier.width(width.dp)) { focused ->
        Box(
            Modifier
                .size(width.dp, height.dp)
                .focusFrame(focused, shape)
                .clip(shape),
        ) {
            Artwork(imageUrl, Modifier.fillMaxSize())
            // Soft sheen from the top right (linear-gradient 200deg in the design).
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(0f to Color(0x1AE8F4F7), 0.42f to Color.Transparent, start = Offset(Float.POSITIVE_INFINITY, 0f), end = Offset(0f, Float.POSITIVE_INFINITY))),
            )
            if (badge != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(14.dp)
                        .height(34.dp)
                        .clip(PillShape)
                        .background(accent)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(badge.toString(), style = GlacierText.body(16, FontWeight.Bold), color = GlacierColors.Void)
                }
            }
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (title != null) {
                Text(title, style = GlacierText.display(20), color = if (focused) accent else GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(caption, style = GlacierText.body(16), color = GlacierColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(caption, style = GlacierText.body(18), color = if (focused) accent else GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "My media": wide library tile with name and title count. */
@Composable
fun LibraryCard(name: String, count: String?, imageUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    GlacierCard(onClick = onClick, modifier = modifier) { focused ->
        Box(
            Modifier
                .size(CardSize.LIBRARY_WIDTH.dp, CardSize.LIBRARY_HEIGHT.dp)
                .focusFrame(focused, shape)
                .clip(shape),
        ) {
            Artwork(imageUrl, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xD105090F), Color(0x2E05090F)))))
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 24.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(name, style = GlacierText.display(30), color = GlacierColors.Ice, maxLines = 1)
                if (count != null) Text(count, style = GlacierText.body(16), color = GlacierColors.Mist)
            }
        }
    }
}

@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier, track: Color = GlacierColors.GlassFill2) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(PillShape)
                .background(LocalAccent.current.main),
        )
    }
}

enum class CardShape { Poster, Square, Round }

/**
 * Library grid tile (design: `libGrid`): artwork, title and one caption line,
 * plus a watched check. Collections give their size in the caption, not a badge.
 */
@Composable
fun GridCard(
    imageUrl: String?,
    title: String,
    caption: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: CardShape = CardShape.Poster,
    watched: Boolean = false,
) {
    val accent = LocalAccent.current.main
    val outline: Shape = when (shape) {
        CardShape.Poster -> RoundedCornerShape(GlacierShapes.RadiusLg)
        CardShape.Square -> RoundedCornerShape(GlacierShapes.RadiusMd)
        CardShape.Round -> PillShape
    }
    val sizes = LocalCardSizes.current
    val width = sizes.posterWidth
    val height = if (shape == CardShape.Poster) sizes.posterHeight else sizes.posterWidth
    GlacierCard(onClick = onClick, modifier = modifier.width(width.dp)) { focused ->
        Box(
            Modifier
                .size(width.dp, height.dp)
                .focusFrame(focused, outline)
                .clip(outline),
        ) {
            Artwork(imageUrl, Modifier.fillMaxSize())
            if (imageUrl == null) {
                // No artwork (genres, some collections): the title stands in for it.
                Text(
                    title,
                    style = GlacierText.display(26),
                    color = GlacierColors.Ice,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(20.dp),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(0f to Color(0x1AE8F4F7), 0.42f to Color.Transparent, start = Offset(Float.POSITIVE_INFINITY, 0f), end = Offset(0f, Float.POSITIVE_INFINITY))),
            )
            if (watched) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(14.dp)
                        .size(34.dp)
                        .clip(PillShape)
                        .background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(GlacierIcons.Check, contentDescription = null, tint = GlacierColors.Void, modifier = Modifier.size(18.dp))
                }
            }
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = GlacierText.display(20), color = if (focused) accent else GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (caption != null) Text(caption, style = GlacierText.body(16), color = GlacierColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
