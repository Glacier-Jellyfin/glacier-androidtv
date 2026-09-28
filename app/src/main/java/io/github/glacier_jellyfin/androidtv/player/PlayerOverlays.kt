package io.github.glacier_jellyfin.androidtv.player

import android.graphics.Bitmap
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import coil3.request.SuccessResult
import coil3.request.bitmapConfig
import coil3.toBitmap
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.Chapter
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.Trickplay
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierCard
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusFrame
import io.github.glacier_jellyfin.androidtv.detail.TrackKind
import io.github.glacier_jellyfin.androidtv.detail.TrackPanel
import io.github.glacier_jellyfin.androidtv.detail.trackOptions
import io.github.glacier_jellyfin.androidtv.ui.Artwork

/** The detail page's track sheet, applied immediately while playing. */
@Composable
fun PlayerTrackPanel(state: PlayerUiState, kind: TrackKind, viewModel: PlayerViewModel) {
    val audio = kind == TrackKind.Audio
    val (rows, indices) = trackOptions(
        if (audio) state.audioTracks else state.subtitles.map { it.track },
        subtitle = !audio,
    )
    val item = state.details?.item
    val prefix = when {
        item == null -> ""
        item.kind == ItemKind.Episode && item.episodeNumber != null -> stringResource(R.string.episode_badge, item.episodeNumber!!) + " · " + item.title + " · "
        else -> item.title + " · "
    }
    val current = if (audio) state.audioIndex else state.subtitleIndex
    TrackPanel(
        title = stringResource(if (audio) R.string.track_audio else R.string.track_subtitles),
        subtitle = prefix + stringResource(R.string.track_applies_now),
        options = rows,
        selected = indices.indexOf(current).coerceAtLeast(0),
        onPick = { i ->
            val index = indices[i]
            if (audio) index?.let { viewModel.pickAudio(it, rows[i].first) } else viewModel.pickSubtitle(index, rows[i].first)
        },
        onDismiss = viewModel::closeTracks,
    )
}

/** Bottom sheet of chapter cards (design: 300 wide, 16:9), starting at the current one. */
@Composable
fun ChapterSheet(chapters: List<Chapter>, positionMs: Long, fallbackImage: String?, onPick: (Chapter) -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val current = chapters.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = current)
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF5050910))))
                .padding(top = 56.dp, bottom = 104.dp),
        ) {
            Text(
                stringResource(R.string.player_chapters),
                style = GlacierText.display(26),
                color = GlacierColors.Ice,
                modifier = Modifier.padding(start = 80.dp, bottom = 22.dp),
            )
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 80.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.focusProperties { onExit = { cancelFocusChange() } },
            ) {
                itemsIndexed(chapters) { index, chapter ->
                    ChapterCard(
                        chapter = chapter,
                        fallbackImage = fallbackImage,
                        onClick = { onPick(chapter) },
                        modifier = if (index == current) Modifier.focusRequester(focus) else Modifier,
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
}

@Composable
private fun ChapterCard(chapter: Chapter, fallbackImage: String?, onClick: () -> Unit, modifier: Modifier) {
    val accent = LocalAccent.current.main
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    GlacierCard(onClick = onClick, modifier = modifier.width(300.dp)) { focused ->
        Box(
            Modifier
                .size(300.dp, 169.dp)
                .focusFrame(focused, shape)
                .clip(shape)
                .background(GlacierColors.Deep),
        ) {
            Artwork(chapter.imageUrl ?: fallbackImage, Modifier.fillMaxSize())
            Text(
                formatTime(chapter.startMs),
                style = GlacierText.mono(17).copy(shadow = Shadow(Color(0xCC000000), blurRadius = 8f)),
                color = GlacierColors.Ice,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 12.dp),
            )
        }
        Text(
            chapter.name,
            style = GlacierText.body(18, FontWeight.SemiBold),
            color = if (focused) accent else GlacierColors.Ice,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/**
 * Subtitles over the video. Media3 has no Compose renderer yet, so this is
 * its SubtitleView, styled from the Android caption settings.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerSubtitles(player: Player, liftForOsd: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = remember {
        SubtitleView(context).apply {
            setUserDefaultStyle()
            setUserDefaultTextSize()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) = view.setCues(cueGroup.cues)
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            view.setCues(emptyList())
        }
    }
    AndroidView(
        factory = { view },
        update = { it.setBottomPaddingFraction(if (liftForOsd) OSD_SUBTITLE_LIFT else SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION) },
        modifier = modifier,
    )
}

/** Share of the height subtitles move up while the OSD covers the bottom. */
private const val OSD_SUBTITLE_LIFT = 0.3f

/** One seek preview picture, cut from its trickplay tile. */
@Composable
fun TrickplayThumb(trickplay: Trickplay, positionMs: Long, headers: Map<String, String>, modifier: Modifier = Modifier) {
    val thumb = (positionMs / trickplay.intervalMs.coerceAtLeast(1)).toInt().coerceIn(0, (trickplay.count - 1).coerceAtLeast(0))
    val perTile = (trickplay.columns * trickplay.rows).coerceAtLeast(1)
    val tile = thumb / perTile
    val cell = thumb % perTile
    val url = trickplay.tileUrls.getOrNull(tile) ?: return
    val bitmap = rememberTile(url, headers) ?: return
    Canvas(modifier) {
        // Fit the thumbnail's own aspect ratio into the frame instead of stretching it.
        val scale = minOf(size.width / trickplay.width, size.height / trickplay.height)
        val width = (trickplay.width * scale).toInt()
        val height = (trickplay.height * scale).toInt()
        drawImage(
            image = bitmap,
            srcOffset = IntOffset((cell % trickplay.columns) * trickplay.width, (cell / trickplay.columns) * trickplay.height),
            srcSize = IntSize(trickplay.width, trickplay.height),
            dstOffset = IntOffset(((size.width - width) / 2).toInt(), ((size.height - height) / 2).toInt()),
            dstSize = IntSize(width, height),
        )
    }
}

/**
 * The last two tiles, so scrubbing across a tile border does not reload.
 * A tile is a large picture (100 thumbnails), hence the small cache and 16-bit pixels.
 */
private val TileCache = LruCache<String, ImageBitmap>(2)

@Composable
private fun rememberTile(url: String, headers: Map<String, String>): ImageBitmap? {
    val context = LocalContext.current
    val tile by produceState(TileCache.get(url), url) {
        if (value != null) return@produceState
        val request = ImageRequest.Builder(context)
            .data(url)
            .httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (k, v) -> set(k, v) } }.build())
            .bitmapConfig(Bitmap.Config.RGB_565)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        value = (result as? SuccessResult)?.image?.toBitmap()?.asImageBitmap()?.also { TileCache.put(url, it) }
    }
    return tile
}
