package io.github.glacier_jellyfin.androidtv.player

import android.graphics.Bitmap
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleStyle
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayback
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
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.Trickplay
import io.github.glacier_jellyfin.androidtv.core.data.media.subtitleFormat
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackMethod
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierCard
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTabs
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusFrame
import io.github.glacier_jellyfin.androidtv.detail.TrackKind
import io.github.glacier_jellyfin.androidtv.detail.TrackPanel
import io.github.glacier_jellyfin.androidtv.detail.trackOptions
import io.github.glacier_jellyfin.androidtv.detail.trackLabel
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import io.github.glacier_jellyfin.androidtv.ui.episodeText
import io.github.glacier_jellyfin.androidtv.ui.qualityText
import io.github.glacier_jellyfin.androidtv.ui.ratingText
import io.github.glacier_jellyfin.androidtv.ui.runtimeText

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
            if (audio) index?.let { viewModel.pickAudio(it, rows[i].full) } else viewModel.pickSubtitle(index, rows[i].full)
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
 * its SubtitleView, styled as set in Settings › Subtitles.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerSubtitles(playback: GlacierPlayback, lift: SubtitleLift, style: SubtitleStyle, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = playback.player
    // A new player (track change, next episode) brings a new ASS renderer, so a new view.
    val view = remember(playback) {
        SubtitleView(context).apply { playback.attachAss(this) }
    }
    LaunchedEffect(view, style) { view.applyStyle(style) }
    var cues by remember { mutableStateOf(emptyList<Cue>()) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            cues = emptyList()
        }
    }
    key(view) {
        AndroidView(
            factory = { view },
            update = {
                it.setBottomPaddingFraction(liftedPadding(lift.fraction))
                it.setCues(cues.map { cue -> cue.placed(style.position).lifted(lift) })
            },
            modifier = modifier,
        )
    }
}

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

/**
 * The OSD's info button: two tabs, the title (movie or episode) and the
 * technical side of the stream. Left/Right switch tabs, OK or Back closes.
 */
@Composable
fun InfoPanel(state: PlayerUiState, progress: PlayerProgress, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val item = state.details?.item ?: return
    val focus = remember { FocusRequester() }
    var technical by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(GlacierShapes.RadiusLg)
    Box(Modifier.fillMaxSize().background(Color(0xA805090F)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(960.dp)
                .clip(shape)
                .background(GlacierColors.Deep)
                .border(1.dp, GlacierColors.GlassBorder2, shape)
                .focusRequester(focus)
                .onKeyEvent { event ->
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter -> {
                            if (event.type == KeyEventType.KeyUp) onDismiss()
                            true
                        }
                        Key.DirectionLeft, Key.DirectionRight -> {
                            if (event.type == KeyEventType.KeyDown) technical = event.key == Key.DirectionRight
                            true
                        }
                        else -> false
                    }
                }
                .focusable()
                .padding(40.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            GlacierTabs(
                labels = listOf(
                    stringResource(if (item.kind == ItemKind.Episode) R.string.player_info_episode else R.string.player_info_movie),
                    stringResource(R.string.player_info_technical),
                ),
                selected = if (technical) 1 else 0,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.height(380.dp)) {
                if (technical) TechnicalInfo(state, Modifier.fillMaxWidth()) else TitleInfo(item, progress)
            }
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
}

@Composable
private fun TitleInfo(item: MediaItem, progress: PlayerProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val crumb = if (item.kind == ItemKind.Episode) {
                listOfNotNull(item.parentTitle, episodeText(item))
            } else {
                listOfNotNull(item.year?.toString(), item.genres.firstOrNull())
            }.joinToString(" · ")
            if (crumb.isNotEmpty()) Text(crumb, style = GlacierText.body(18), color = OsdSecondary)
            Text(item.title, style = GlacierText.display(34), color = GlacierColors.Ice, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                item.year?.takeIf { item.kind == ItemKind.Episode }?.toString(),
                (progress.durationMs / 60_000).toInt().takeIf { it > 0 }?.let { runtimeText(it) } ?: item.runtimeMinutes?.let { runtimeText(it) },
                item.officialRating,
                item.communityRating?.let { "★ " + ratingText(it) },
                qualityText(item.quality),
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = GlacierText.body(18), color = OsdSecondary)
        }
        item.overview?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = GlacierText.body(20), color = GlacierColors.Ice, maxLines = 7, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The info sheet's technical tab: stream, decoders and buffer, refreshed every second. */
@Composable
private fun TechnicalInfo(state: PlayerUiState, modifier: Modifier) {
    val player = state.player
    val stats by produceState<StreamStats?>(null, player, state.videoDecoder, state.audioDecoder) {
        while (player != null) {
            value = player.streamStats(state.videoDecoder, state.audioDecoder)
            delay(1_000)
        }
    }
    val method = state.method?.let {
        stringResource(
            when (it) {
                PlaybackMethod.DirectPlay -> R.string.player_direct_play
                PlaybackMethod.DirectStream -> R.string.player_direct_stream
                PlaybackMethod.Transcode -> R.string.player_transcode
            },
        )
    }
    val audio = state.audioTracks.firstOrNull { it.index == state.audioIndex }?.let { trackLabel(it, subtitle = false) }
    val subtitle = state.subtitles.firstOrNull { it.track.index == state.subtitleIndex }
        ?.let { listOfNotNull(trackLabel(it.track, subtitle = true), subtitleFormat(it.track.codec)).joinToString(" · ") }
        ?: stringResource(R.string.track_off)
    val rows = listOfNotNull(
        method?.let { R.string.player_info_stream to it },
        stats?.video?.let { R.string.player_info_video to it },
        stats?.videoDecoder?.let { R.string.player_info_video_decoder to it },
        audio?.let { R.string.player_audio to it },
        stats?.audio?.let { R.string.player_info_audio_stream to it },
        stats?.audioDecoder?.let { R.string.player_info_audio_decoder to it },
        R.string.player_subtitles to subtitle,
        stats?.let { R.string.player_info_buffer to stringResource(R.string.player_info_seconds, it.bufferSeconds) },
        stats?.droppedFrames?.let { R.string.player_info_dropped to it.toString() },
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(stringResource(label), style = GlacierText.body(17), color = OsdSecondary, modifier = Modifier.width(170.dp))
                Text(value, style = GlacierText.body(17, FontWeight.SemiBold), color = GlacierColors.Ice)
            }
        }
    }
}
