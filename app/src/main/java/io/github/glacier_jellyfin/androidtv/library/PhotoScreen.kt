package io.github.glacier_jellyfin.androidtv.library

import android.text.format.DateFormat
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.size.Size
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderMedia
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaPhoto
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.KeyCap
import io.github.glacier_jellyfin.androidtv.core.designsystem.KeyCapIcon
import io.github.glacier_jellyfin.androidtv.core.designsystem.KeyHint
import io.github.glacier_jellyfin.androidtv.core.designsystem.LeftToRight
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalReduceMotion
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.core.designsystem.blockKeysWhile
import io.github.glacier_jellyfin.androidtv.core.designsystem.endKey
import io.github.glacier_jellyfin.androidtv.core.designsystem.startKey
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.PageEdge
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A folder's photos and videos full screen, in the folder page's order. A video plays right here.
 * Left and right browse; OK on a photo starts a slideshow, which plays the videos it comes to, and OK
 * during a slideshow stops it (a video then pauses here); OK on a video outside a slideshow opens the full
 * player where it is, and Back from there comes back here. Down and
 * the Info key open the details of the photo or video (file, camera, exposure) and end a slideshow;
 * Back closes them first. Name, date and position show for a moment after each change and switch to the next
 * item at once; the keys show as key caps for a few seconds when the viewer opens and again on Up. A line along the
 * bottom edge shows how far a video has played, or a photo's time in the slideshow. Browsing to a photo that is not
 * loaded yet shows its small thumbnail blurred with a loader until the photo is sharp.
 */
@OptIn(UnstableApi::class) // ContentFrame
@Composable
fun PhotoScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    viewModel: PhotoViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val video by viewModel.video.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)
    // Back from the full player the slideshow goes on; leaving, nothing plays on behind.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }
    val reduceMotion = LocalReduceMotion.current
    val focus = remember { FocusRequester() }
    val direction = LocalLayoutDirection.current
    // After the details close the viewer takes the keys back.
    LaunchedEffect(info == null) { runCatching { focus.requestFocus() } }
    // A slideshow or a video runs on its own: the TV must not dim or start its screensaver meanwhile.
    val view = LocalView.current
    val running = state.slideshow || video.player != null
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }
    val context = LocalContext.current
    val item = state.current
    val shownIndex = state.index
    // The photo whose picture failed to load, so OK retries it; and the last photo that was sharp, which stays dimmed
    // behind a photo that has neither its thumbnail nor itself yet.
    var failedId by remember { mutableStateOf<UUID?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var lastSharp by remember { mutableStateOf<MediaPhoto?>(null) }
    var overlayKey by remember { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf(true) }
    LaunchedEffect(overlayKey, item?.id, state.slideshow) {
        overlay = true
        delay(if (state.slideshow) SLIDESHOW_OVERLAY_MS else OVERLAY_MS)
        overlay = false
    }
    // The key caps show when the viewer opens and again on Up.
    var hintKey by remember { mutableIntStateOf(0) }
    var hints by remember { mutableStateOf(true) }
    // The time counts from the first photo or video on screen, not from the loader before it.
    val hasItem = item != null
    LaunchedEffect(hintKey, hasItem) {
        if (!hasItem) return@LaunchedEffect
        hints = true
        delay(HINTS_MS)
        hints = false
    }
    // The neighbours load ahead, so browsing does not wait for them: the photo and its thumbnail.
    LaunchedEffect(state.media, state.index) {
        val loader = SingletonImageLoader.get(context)
        listOf(state.index + 1, state.index - 1)
            .mapNotNull { state.media.getOrNull(it) as? FolderMedia.Photo }.forEach {
                loader.enqueue(ImageRequest.Builder(context).data(it.photo.thumbUrl).build())
                loader.enqueue(
                    ImageRequest.Builder(context).data(it.photo.fullUrl).size(Size.ORIGINAL).build(),
                )
            }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            // The loader shows while no photo is there yet: only Back gets through.
            .blockKeysWhile(state.loading && state.current == null)
            .onPreviewKeyEvent { event ->
                // Without photos the keys belong to the retry button.
                // While the details are open the keys belong to the panel.
                if (info != null || event.type != KeyEventType.KeyDown || state.media.isEmpty()) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    direction.startKey -> viewModel.previous()

                    direction.endKey -> viewModel.next()

                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter ->
                        // On a photo that did not load OK tries again.
                        if (failedId != null && failedId == state.current?.id) {
                            failedId = null
                            retry++
                        } else {
                            viewModel.select()
                        }

                    Key.MediaPlayPause -> viewModel.playPause()

                    Key.DirectionDown, Key.Info -> viewModel.openDetails()

                    // Up only brings the name and the key caps back.
                    Key.DirectionUp -> hintKey++

                    else -> return@onPreviewKeyEvent false
                }
                overlayKey++
                true
            }
            .focusable(),
    ) {
        val current = state.current
        when {
            state.loading && current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SpinningDiamond(110)
            }

            state.failed -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            ) {
                Text(stringResource(R.string.photo_error), style = GlacierText.display(30), color = GlacierColors.Ice)
                PillButton(stringResource(R.string.action_retry), onClick = viewModel::load, primary = true)
            }

            item != null -> AnimatedContent(
                targetState = item,
                contentKey = { it.id },
                transitionSpec = {
                    when {
                        reduceMotion -> fadeIn(tween(FADE_MS)) togetherWith fadeOut(tween(FADE_MS))

                        // The slideshow blends slowly, the new picture settling from a little larger.
                        state.slideshow -> {
                            val enter = fadeIn(tween(SLIDESHOW_MS)) + scaleIn(tween(SLIDESHOW_MS), initialScale = 1.06f)
                            enter togetherWith fadeOut(tween(SLIDESHOW_MS))
                        }

                        // Browsing slides the picture in from the side it comes from.
                        else -> {
                            val side = if (state.forward) 1 else -1
                            val enter = slideInHorizontally(tween(BROWSE_MS)) { it / 5 * side } +
                                fadeIn(tween(BROWSE_MS))
                            val exit = slideOutHorizontally(tween(BROWSE_MS)) { -it / 5 * side } +
                                fadeOut(tween(BROWSE_MS))
                            enter togetherWith exit
                        }
                    }
                },
                label = "photo",
            ) {
                when (it) {
                    is FolderMedia.Photo -> key(retry) {
                        PhotoImage(
                            photo = it.photo,
                            previous = lastSharp,
                            reduceMotion = reduceMotion,
                            onSharp = { photo -> lastSharp = photo },
                            onFailed = { failed ->
                                if (failed) {
                                    failedId = it.id
                                } else if (failedId == it.id) {
                                    failedId = null
                                }
                            },
                        )
                    }

                    is FolderMedia.Video -> {
                        val player = video.player.takeIf { _ -> video.id == it.id }
                        // The frame shows until the stream has its first picture, then the video over it.
                        VideoStill(it.item, playing = player != null)
                        if (player != null) {
                            ContentFrame(
                                player = player,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = overlay && item != null && info == null,
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
                    item?.name.orEmpty(),
                    style = GlacierText.display(34),
                    color = GlacierColors.Ice,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                longTakenText(item?.date)?.let { Text(it, style = GlacierText.body(21), color = GlacierColors.Mist) }
            }
        }

        // The keys as key caps at the bottom end, apart from the name.
        AnimatedVisibility(
            visible = hints && item != null && info == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = PageEdge.dp, bottom = 56.dp),
        ) {
            ViewerHints(video = item is FolderMedia.Video, slideshow = state.slideshow)
        }

        // Where in the folder this is, small in the top corner, shown and hidden with the name.
        AnimatedVisibility(
            visible = overlay && state.media.size > 1 && info == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = PageEdge.dp),
        ) {
            // "3 / 7" reads left to right in every language.
            LeftToRight {
                Text(
                    stringResource(R.string.photo_position, shownIndex + 1, state.media.size),
                    style = GlacierText.body(18, FontWeight.SemiBold),
                    color = GlacierColors.Ice,
                    modifier = Modifier
                        .overlayPill()
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
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
                SlideProgress(
                    key = state.index,
                    durationMs = state.slideMs,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
        }

        info?.let { PhotoInfoPanel(it, video, viewModel::transcodeStatus, onDismiss = viewModel::closeDetails) }
    }
}

/** A thin line filling from left to right over [durationMs], from the start again for each new [key]. */
@Composable
private fun SlideProgress(
    key: Int,
    durationMs: Long,
    modifier: Modifier,
) {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key, durationMs) { progress.animateTo(1f, tween(durationMs.toInt(), easing = LinearEasing)) }
    Box(
        modifier
            .fillMaxWidth(progress.value)
            .height(SLIDE_PROGRESS_HEIGHT.dp)
            .background(LocalAccent.current.main),
    )
}

/** A pill over the picture that reads on black bars and on bright photos alike: a dark, slightly lifted fill and an outline that shows next to black. */
private fun Modifier.overlayPill(): Modifier = this
    .clip(PillShape)
    .background(OverlayPillFill)
    .background(GlacierColors.GlassFill2)
    .border(PILL_BORDER.dp, GlacierColors.Ice.copy(alpha = PILL_BORDER_ALPHA), PillShape)

/** The keys of the viewer: browse, OK, details; in a slideshow only OK to pause it. */
@Composable
private fun ViewerHints(video: Boolean, slideshow: Boolean) {
    Row(
        Modifier
            .overlayPill()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (slideshow) {
            KeyHint(stringResource(R.string.player_pause)) { KeyCap("OK", strong = true) }
        } else {
            KeyHint(stringResource(R.string.photo_hint_browse)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    KeyCapIcon(GlacierIcons.ChevronLeft, strong = true)
                    KeyCapIcon(GlacierIcons.ChevronRight, strong = true)
                }
            }
            KeyHint(
                stringResource(if (video) R.string.player_play else R.string.photo_hint_slideshow),
            ) { KeyCap("OK", strong = true) }
            KeyHint(stringResource(R.string.photo_hint_details)) { KeyCapIcon(GlacierIcons.ChevronDown, strong = true) }
        }
    }
}

/**
 * The whole photo, as large as the screen allows. Until it is sharp its thumbnail shows blurred with a loader (the
 * thumbnail is decoded tiny and drawn smoothed up, which blurs it on every Android version); when that is not there
 * either, [previous] stays dimmed. A photo that is in the memory cache comes without any of it. A line and a retry hint
 * show when it does not load.
 */
@Composable
private fun PhotoImage(
    photo: MediaPhoto,
    previous: MediaPhoto?,
    reduceMotion: Boolean,
    onSharp: (MediaPhoto) -> Unit,
    onFailed: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    // Preloaded photos sit in the memory cache under their URL.
    val cached = remember(photo.id) {
        SingletonImageLoader.get(context).memoryCache?.get(MemoryCache.Key(photo.fullUrl)) != null
    }
    var loaded by remember(photo.id) { mutableStateOf(false) }
    var assumedCached by remember(photo.id) { mutableStateOf(cached) }
    var thumbnail by remember(photo.id) { mutableStateOf(false) }
    var failed by remember(photo.id) { mutableStateOf(false) }
    LaunchedEffect(failed) { onFailed(failed) }
    DisposableEffect(photo.id) { onDispose { onFailed(false) } }
    // A cache entry the picture then does not use must not leave a black screen without a loader.
    LaunchedEffect(photo.id) {
        if (assumedCached) {
            delay(CACHE_CLAIM_MS)
            if (!loaded) assumedCached = false
        }
    }
    val scope = rememberCoroutineScope()

    /** Removes a failed URL from the disk cache and the memory cache [memoryKey]. */
    fun dropCached(url: String, memoryKey: String) {
        val loader = SingletonImageLoader.get(context)
        loader.memoryCache?.remove(MemoryCache.Key(memoryKey))
        scope.launch(Dispatchers.IO) { loader.diskCache?.remove(url) }
    }
    val sharp = loaded || assumedCached
    val alpha by animateFloatAsState(
        if (sharp) 1f else 0f,
        tween(if (reduceMotion) FADE_MS else CROSSFADE_MS),
        label = "sharp",
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (failed) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.photo_error), style = GlacierText.body(21), color = GlacierColors.Mist)
                KeyHint(stringResource(R.string.action_retry)) { KeyCap("OK", strong = true) }
            }
        } else {
            // Under the sharp photo while it fades in.
            if (alpha < 1f) {
                if (!thumbnail && previous != null && previous.id != photo.id) {
                    AsyncImage(
                        model = previous.fullUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        alpha = DIMMED_ALPHA,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(photo.thumbUrl)
                        .size(BLUR_PX, BLUR_PX)
                        .memoryCacheKey("blur:${photo.thumbUrl}")
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    onSuccess = { thumbnail = true },
                    onError = { dropCached(photo.thumbUrl, "blur:${photo.thumbUrl}") },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (!sharp) SpinningDiamond(LOADER_SIZE)
            AsyncImage(
                model = photo.fullUrl,
                contentDescription = photo.name,
                contentScale = ContentScale.Fit,
                onSuccess = {
                    loaded = true
                    onSharp(photo)
                },
                onError = { error ->
                    // A broken entry (an aborted transfer) must not poison the photo: the retry goes to the network.
                    Log.w(TAG, "Loading the photo ${photo.id} failed", error.result.throwable)
                    dropCached(photo.fullUrl, photo.fullUrl)
                    failed = true
                },
                modifier = Modifier.fillMaxSize().alpha(alpha),
            )
        }
    }
}

/**
 * A video's frame until it plays, whole like a photo and like the video after it (a portrait video's frame
 * is not cropped to the screen); the play sign while its stream is not open yet.
 */
@Composable
private fun VideoStill(video: MediaItem, playing: Boolean) {
    val accent = LocalAccent.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = video.thumbUrl,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (!playing) {
            Box(
                Modifier
                    .size(96.dp)
                    .clip(PillShape)
                    .background(accent.main),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    GlacierIcons.Play,
                    contentDescription = null,
                    tint = GlacierColors.Void,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
}

/** Transitions: browsing by hand, the slideshow's slow blend, and a plain fade with "reduce motion". */
private const val BROWSE_MS = 350
private const val SLIDESHOW_MS = 1_200
private const val FADE_MS = 300

/** "Tuesday, 22 July 2025 · 08:00" in the app language: when a photo or video was taken, in full. */
@Composable
internal fun longTakenText(date: LocalDateTime?): String? {
    date ?: return null
    val locale = LocalLocale.current.platformLocale
    val day = date.format(
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMMyyyy"), locale),
    )
    val time = date.format(
        DateTimeFormatter.ofPattern(
            DateFormat.getBestDateTimePattern(
                locale,
                if (DateFormat.is24HourFormat(LocalContext.current)) "Hm" else "hma",
            ),
            locale,
        ),
    )
    return "$day · $time"
}

private const val TAG = "Photos"

/** The blurred thumbnail: decoded to this many pixels on its long side, then drawn up to the screen. */
private const val BLUR_PX = 48
private const val CROSSFADE_MS = 250
private val OverlayPillFill = Color(0xE60F1A24)
private const val PILL_BORDER = 1.5f
private const val PILL_BORDER_ALPHA = 0.35f
private const val DIMMED_ALPHA = 0.35f
private const val LOADER_SIZE = 72

/** How long a photo from the memory cache may take to show before the thumbnail and loader come after all. */
private const val CACHE_CLAIM_MS = 400L

/** How long the key caps stay. */
private const val HINTS_MS = 5_000L

/** The slideshow's progress line at the bottom edge. */
private const val SLIDE_PROGRESS_HEIGHT = 5

/** How long name and position stay after a change; in a slideshow shorter, so they do not lie over every photo. */
private const val OVERLAY_MS = 4_000L
private const val SLIDESHOW_OVERLAY_MS = 1_500L
