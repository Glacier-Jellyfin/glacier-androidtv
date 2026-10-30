package io.github.glacier_jellyfin.androidtv.library

import android.text.format.DateFormat
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import androidx.tv.material3.Icon
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaPhoto
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderMedia
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalReduceMotion
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.ui.PageEdge
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A folder's photos and videos full screen, in the folder page's order. A video plays right here.
 * Left and right browse; OK on a photo starts or stops a slideshow, which plays the videos it comes
 * to; OK on a video opens the full player where it is, and Back from there comes back here. Name,
 * date and position show for a moment after each change; a line along the bottom edge shows how far
 * a video has played, or a photo's time in the slideshow.
 */
@OptIn(UnstableApi::class) // ContentFrame
@Composable
fun PhotoScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    viewModel: PhotoViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val video by viewModel.video.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)
    // Back from the full player the slideshow goes on; leaving, nothing plays on behind.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }
    val reduceMotion = LocalReduceMotion.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // A slideshow or a video runs on its own: the TV must not dim or start its screensaver meanwhile.
    val view = LocalView.current
    val running = state.slideshow || video.player != null
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }
    var overlayKey by remember { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf(true) }
    LaunchedEffect(overlayKey, state.index, state.slideshow) {
        overlay = true
        delay(OVERLAY_MS)
        overlay = false
    }
    // The neighbours load ahead, so browsing does not wait for them.
    val context = LocalContext.current
    LaunchedEffect(state.media, state.index) {
        val loader = SingletonImageLoader.get(context)
        listOf(state.index + 1, state.index - 1).mapNotNull { state.media.getOrNull(it) as? FolderMedia.Photo }.forEach {
            loader.enqueue(ImageRequest.Builder(context).data(it.photo.fullUrl).build())
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                // Without photos the keys belong to the retry button.
                if (event.type != KeyEventType.KeyDown || state.media.isEmpty()) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> viewModel.previous()
                    Key.DirectionRight -> viewModel.next()
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> viewModel.select()
                    Key.MediaPlayPause -> viewModel.playPause()
                    // Up and down only bring the name back.
                    Key.DirectionUp, Key.DirectionDown -> Unit
                    else -> return@onPreviewKeyEvent false
                }
                overlayKey++
                true
            }
            .focusable(),
    ) {
        val current = state.current
        when {
            state.loading && current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SpinningDiamond(110) }
            state.failed -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            ) {
                Text(stringResource(R.string.photo_error), style = GlacierText.display(30), color = GlacierColors.Ice)
                PillButton(stringResource(R.string.action_retry), onClick = viewModel::load, primary = true)
            }
            current != null -> AnimatedContent(
                targetState = current,
                contentKey = { it.id },
                transitionSpec = {
                    when {
                        reduceMotion -> fadeIn(tween(FADE_MS)) togetherWith fadeOut(tween(FADE_MS))
                        // The slideshow blends slowly, the new picture settling from a little larger.
                        state.slideshow -> (fadeIn(tween(SLIDESHOW_MS)) + scaleIn(tween(SLIDESHOW_MS), initialScale = 1.06f)) togetherWith
                            fadeOut(tween(SLIDESHOW_MS))
                        // Browsing slides the picture in from the side it comes from.
                        else -> {
                            val side = if (state.forward) 1 else -1
                            (slideInHorizontally(tween(BROWSE_MS)) { it / 5 * side } + fadeIn(tween(BROWSE_MS))) togetherWith
                                (slideOutHorizontally(tween(BROWSE_MS)) { -it / 5 * side } + fadeOut(tween(BROWSE_MS)))
                        }
                    }
                },
                label = "photo",
            ) {
                when (it) {
                    is FolderMedia.Photo -> PhotoImage(it.photo)
                    is FolderMedia.Video -> {
                        val player = video.player.takeIf { _ -> video.id == it.id }
                        // The frame shows until the stream has its first picture, then the video over it.
                        VideoStill(it.item, playing = player != null)
                        if (player != null) ContentFrame(player = player, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = overlay && current != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD905090F))))
                    .padding(start = PageEdge.dp, end = PageEdge.dp, top = 120.dp, bottom = 56.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    current?.name.orEmpty(),
                    style = GlacierText.display(34),
                    color = GlacierColors.Ice,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                longTakenText(current?.date)?.let { Text(it, style = GlacierText.body(21), color = GlacierColors.Mist) }
            }
        }

        // Where in the folder this is, small in the top corner, shown and hidden with the name.
        AnimatedVisibility(
            visible = overlay && state.media.size > 1,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = PageEdge.dp),
        ) {
            Text(
                stringResource(R.string.photo_position, state.index + 1, state.media.size),
                style = GlacierText.body(18, FontWeight.SemiBold),
                color = GlacierColors.Ice,
                modifier = Modifier
                    .clip(PillShape)
                    .background(Color(0xB305090F))
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }

        // Along the bottom edge: how far a video has played, or the slideshow's time for a photo.
        when {
            current is FolderMedia.Video && video.player != null -> Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(video.progress)
                    .height(SLIDE_PROGRESS_HEIGHT.dp)
                    .background(LocalAccent.current.main),
            )
            state.slideshow && current is FolderMedia.Photo ->
                SlideProgress(key = state.index, durationMs = state.slideMs, modifier = Modifier.align(Alignment.BottomStart))
        }
    }
}

/** A thin line filling from left to right over [durationMs], from the start again for each new [key]. */
@Composable
private fun SlideProgress(key: Int, durationMs: Long, modifier: Modifier) {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key, durationMs) { progress.animateTo(1f, tween(durationMs.toInt(), easing = LinearEasing)) }
    Box(
        modifier
            .fillMaxWidth(progress.value)
            .height(SLIDE_PROGRESS_HEIGHT.dp)
            .background(LocalAccent.current.main),
    )
}

/** The whole photo, as large as the screen allows; a line when it does not load. */
@Composable
private fun PhotoImage(photo: MediaPhoto) {
    var failed by remember(photo.id) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (failed) {
            Text(stringResource(R.string.photo_error), style = GlacierText.body(21), color = GlacierColors.Mist)
        } else {
            AsyncImage(
                model = photo.fullUrl,
                contentDescription = photo.name,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** A video's frame until it plays; the play sign while its stream is not open yet. */
@Composable
private fun VideoStill(video: MediaItem, playing: Boolean) {
    val accent = LocalAccent.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Artwork(video.thumbUrl, Modifier.fillMaxSize())
        if (!playing) Box(
            Modifier
                .size(96.dp)
                .clip(PillShape)
                .background(accent.main),
            contentAlignment = Alignment.Center,
        ) {
            Icon(GlacierIcons.Play, contentDescription = null, tint = GlacierColors.Void, modifier = Modifier.size(36.dp))
        }
    }
}

/** Transitions: browsing by hand, the slideshow's slow blend, and a plain fade with "reduce motion". */
private const val BROWSE_MS = 350
private const val SLIDESHOW_MS = 1_200
private const val FADE_MS = 300

/** "Tuesday, 22 July 2025 · 08:00" in the app language: when a photo or video was taken, in full. */
@Composable
private fun longTakenText(date: LocalDateTime?): String? {
    date ?: return null
    val locale = LocalLocale.current.platformLocale
    val day = date.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMMyyyy"), locale))
    val time = date.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (DateFormat.is24HourFormat(LocalContext.current)) "Hm" else "hma"), locale))
    return "$day · $time"
}

/** The slideshow's progress line at the bottom edge. */
private const val SLIDE_PROGRESS_HEIGHT = 5

/** How long name and position stay after a change. */
private const val OVERLAY_MS = 4_000L
