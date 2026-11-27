package io.github.glacier_jellyfin.androidtv.library

import android.text.format.DateFormat
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
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
import io.github.glacier_jellyfin.androidtv.core.designsystem.KeyHint
import io.github.glacier_jellyfin.androidtv.core.designsystem.LeftToRight
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalReduceMotion
import io.github.glacier_jellyfin.androidtv.core.designsystem.OptionRow
import io.github.glacier_jellyfin.androidtv.core.designsystem.OptionsPanel
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.core.designsystem.blockKeysWhile
import io.github.glacier_jellyfin.androidtv.core.designsystem.endKey
import io.github.glacier_jellyfin.androidtv.core.designsystem.startKey
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.ui.BlurHashCache
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.ConfirmDeleteDialog
import io.github.glacier_jellyfin.androidtv.ui.PageEdge
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.glacier_jellyfin.androidtv.ui.blurHashComponents
import io.github.glacier_jellyfin.androidtv.ui.blurHashSize
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A folder's photos and videos full screen, in the folder page's order. A video plays right here.
 * Left and right browse; OK on a photo starts a slideshow, which plays the videos it comes to, and OK
 * during a slideshow stops it (a video then pauses here); OK on a video outside a slideshow opens the full
 * player where it is, and Back from there comes back here. Down and
 * the Info key open the details of the photo or video (file, camera, exposure) and end a slideshow;
 * Back closes them first. Holding OK or the Menu key opens the options: slideshow or play, details and, when the
 * server allows it, delete after a confirmation; a slideshow stops. A deleted item leaves at once and the viewer
 * stays at the same place, and the screen goes back when none is left. Name, date and position show for a moment after each change and switch to the next
 * item at once; the keys show as key caps for a few seconds when the viewer opens and again on Up. A line along the
 * bottom edge shows how far a video has played, or a photo's time in the slideshow. Browsing to a photo that is not
 * loaded yet shows its BlurHash with a loader until the photo is sharp (its blurred thumbnail when it has none). The
 * next photos in the browsing direction, and the one behind, load ahead. With "Blurred
 * background" on (Settings) a photo has a blurred, dimmed copy of itself behind it instead of black bars; a portrait
 * video gets that from the player, which blurs the same decode into the frame.
 */
@OptIn(UnstableApi::class) // ContentFrame
@Composable
fun PhotoScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    onBack: () -> Unit,
    viewModel: PhotoViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val video by viewModel.video.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()
    val blurred by viewModel.blurredBackground.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)
    // The last photo or video was deleted: nothing is left to show.
    LaunchedEffect(state.closed) { if (state.closed) onBack() }
    // Back from the full player the slideshow goes on; leaving, nothing plays on behind.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }
    val reduceMotion = LocalReduceMotion.current
    val focus = remember { FocusRequester() }
    val direction = LocalLayoutDirection.current
    // After the details or the options close the viewer takes the keys back.
    LaunchedEffect(info == null, state.menu == null) { runCatching { focus.requestFocus() } }
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
    // Holding OK opened the options: the release that follows must not also be an OK.
    var heldOk by remember { mutableStateOf(false) }
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
    // The neighbours load ahead, most in the browsing direction, so browsing does not wait for them: the photo, and
    // its placeholder (decoded now, or the thumbnail when it has no usable BlurHash).
    LaunchedEffect(state.media, state.index) {
        val loader = SingletonImageLoader.get(context)
        val step = if (state.forward) 1 else -1
        val indices = (1..PRELOAD_AHEAD).map { state.index + step * it } +
            (1..PRELOAD_BEHIND).map { state.index - step * it }
        indices.mapNotNull { state.media.getOrNull(it) as? FolderMedia.Photo }.forEach {
            val photo = it.photo
            loader.enqueue(ImageRequest.Builder(context).data(photo.fullUrl).size(Size.ORIGINAL).build())
            if (!photo.hasPlaceholder()) {
                loader.enqueue(ImageRequest.Builder(context).data(photo.thumbUrl).build())
            } else {
                withContext(Dispatchers.Default) { photo.placeholder() }
            }
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
                // While the details or the options are open the keys belong to the panel.
                if (info != null || state.menu != null || state.media.isEmpty()) return@onPreviewKeyEvent false
                val isOk = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (isOk) {
                    // Holding OK repeats the key: the first repeat opens the options, the release does nothing.
                    // OK acts on the release, so a held key never starts the slideshow or the player.
                    val native = event.nativeKeyEvent
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            if (native.repeatCount == 0) heldOk = false
                            if (native.repeatCount == 1) {
                                heldOk = true
                                viewModel.showMenu(PhotoMenu.Options)
                            }
                        }

                        KeyEventType.KeyUp -> {
                            if (!heldOk) {
                                // On a photo that did not load OK tries again.
                                if (failedId != null && failedId == state.current?.id) {
                                    failedId = null
                                    retry++
                                } else {
                                    viewModel.select()
                                }
                            }
                            heldOk = false
                        }
                    }
                    overlayKey++
                    return@onPreviewKeyEvent true
                }
                if (event.key == Key.Menu) {
                    if (event.type == KeyEventType.KeyUp) viewModel.showMenu(PhotoMenu.Options)
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    direction.startKey -> viewModel.previous()

                    direction.endKey -> viewModel.next()

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
        // Behind a photo: a blurred copy of it in place of the black bars. A portrait video brings its own with its frame.
        if (blurred) {
            Crossfade(
                targetState = (current as? FolderMedia.Photo)?.photo?.fullUrl,
                animationSpec = tween(if (reduceMotion) FADE_MS else BROWSE_MS),
                label = "backdrop",
            ) { url -> if (url != null) BlurredPhotoBackdrop(url) }
        }
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
            visible = overlay && item != null && info == null && state.menu == null,
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

        // The keys at the bottom end, apart from the name, over a soft dark fade along the bottom edge.
        AnimatedVisibility(
            visible = hints && item != null && info == null && state.menu == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).fillMaxWidth(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                    .padding(end = PageEdge.dp, top = 90.dp, bottom = 56.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                ViewerHints(video = item is FolderMedia.Video, slideshow = state.slideshow)
            }
        }

        // Where in the folder this is, small in the top corner, shown and hidden with the name.
        AnimatedVisibility(
            visible = overlay && state.media.size > 1 && info == null && state.menu == null,
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

        current?.let { PhotoMenuHost(it, state.menu, viewModel) }

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

/**
 * What [menu] has open over the viewer for [item]: its options in a panel, or the question before deleting it.
 * Options: start the slideshow or play the video, the details, and delete when the server allows it.
 */
@Composable
private fun PhotoMenuHost(
    item: FolderMedia,
    menu: PhotoMenu?,
    viewModel: PhotoViewModel,
) {
    val video = item is FolderMedia.Video
    when (menu) {
        null -> Unit

        PhotoMenu.ConfirmDelete -> ConfirmDeleteDialog(
            title = stringResource(if (video) R.string.video_delete_title else R.string.photo_delete_title),
            body = stringResource(R.string.photo_delete_body, item.name),
            confirmLabel = stringResource(R.string.photo_delete),
            onConfirm = viewModel::deleteCurrent,
            onDismiss = { viewModel.showMenu(null) },
        )

        PhotoMenu.Options -> {
            val first = remember { FocusRequester() }
            OptionsPanel(
                title = item.name,
                subtitle = longTakenText(item.date),
                onDismiss = { viewModel.showMenu(null) },
                first = first,
            ) {
                OptionRow(
                    stringResource(if (video) R.string.player_play else R.string.photo_slideshow_start),
                    stringResource(if (video) R.string.photo_play_desc else R.string.photo_slideshow_desc),
                    {
                        viewModel.showMenu(null)
                        viewModel.select()
                    },
                    Modifier.focusRequester(first),
                    GlacierIcons.Play,
                )
                OptionRow(
                    stringResource(R.string.photo_hint_details),
                    stringResource(R.string.photo_details_desc),
                    {
                        viewModel.showMenu(null)
                        viewModel.openDetails()
                    },
                    icon = GlacierIcons.Info,
                )
                if (item.canDelete) {
                    OptionRow(
                        stringResource(R.string.photo_delete),
                        stringResource(if (video) R.string.video_delete_desc else R.string.photo_delete_desc),
                        { viewModel.showMenu(PhotoMenu.ConfirmDelete) },
                        icon = GlacierIcons.Trash,
                    )
                }
            }
        }
    }
}

/** Whether the photo has a BlurHash that decodes and a shape to draw it in; otherwise its thumbnail stands in. */
private fun MediaPhoto.hasPlaceholder(): Boolean {
    val hash = blurHash ?: return false
    return aspectRatio != null && blurHashComponents(hash) != null
}

private fun MediaPhoto.placeholderSize(): Pair<Int, Int> = blurHashSize(aspectRatio ?: 1f)

/** The decoded placeholder if it is in the cache already; cheap, safe on the main thread. */
private fun MediaPhoto.cachedPlaceholder(): ImageBitmap? {
    val hash = blurHash ?: return null
    if (aspectRatio == null) return null
    val (w, h) = placeholderSize()
    return BlurHashCache.get(hash, w, h)
}

/** The placeholder, decoded now when it is not cached: call off the main thread. */
private fun MediaPhoto.placeholder(): ImageBitmap? {
    val hash = blurHash ?: return null
    if (aspectRatio == null) return null
    val (w, h) = placeholderSize()
    return BlurHashCache.decode(hash, w, h)
}

/**
 * The keys of the viewer as plain glyphs and labels, with no box behind them: browse, OK, details and the options
 * (hold OK, or Menu); in a slideshow only OK to pause it. The options' ring fills once as the row appears (at once with
 * "reduce motion"). The arrows are the physical keys and mirror with the layout like the browsing does.
 */
@Composable
private fun ViewerHints(video: Boolean, slideshow: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(HINT_GAP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (slideshow) {
            HintItem(stringResource(R.string.player_pause)) { OkGlyph() }
        } else {
            HintItem(stringResource(R.string.photo_hint_browse)) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    HintIcon(GlacierIcons.ChevronLeft)
                    HintIcon(GlacierIcons.ChevronRight)
                }
            }
            HintItem(stringResource(if (video) R.string.player_play else R.string.photo_hint_slideshow)) { OkGlyph() }
            HintItem(stringResource(R.string.photo_hint_details)) { HintIcon(GlacierIcons.ChevronDown) }
            HintItem(stringResource(R.string.photo_hint_options)) { OptionsRing() }
        }
    }
}

/** A key's [glyph] and what it does; the soft shadow keeps the label readable on bright photos. */
@Composable
private fun HintItem(label: String, glyph: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        glyph()
        Text(label, style = hintStyle(FontWeight.Medium, HINT_TEXT_SP), color = GlacierColors.Ice)
    }
}

@Composable
private fun HintIcon(icon: ImageVector) =
    Icon(icon, contentDescription = null, tint = GlacierColors.Ice, modifier = Modifier.size(HINT_ICON.dp))

/** The OK key as bold small text. */
@Composable
private fun OkGlyph() = Text("OK", style = hintStyle(FontWeight.Bold, HINT_OK_SP), color = GlacierColors.Ice)

/** OK in a thin ring whose accent arc runs round once as it appears; the rest of the ring is a faint track. */
@Composable
private fun OptionsRing() {
    val accent = LocalAccent.current.main
    val reduceMotion = LocalReduceMotion.current
    val sweep = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) sweep.snapTo(1f) else sweep.animateTo(1f, tween(RING_MS, easing = FastOutSlowInEasing))
    }
    Box(
        Modifier
            .size(RING_SIZE.dp)
            .drawBehind {
                val stroke = RING_STROKE.dp.toPx()
                val inset = stroke / 2
                val arc = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                val at = Offset(inset, inset)
                drawArc(GlacierColors.Ice.copy(alpha = 0.28f), 0f, 360f, false, at, arc, style = Stroke(stroke))
                drawArc(
                    accent,
                    startAngle = -90f,
                    sweepAngle = 360f * sweep.value,
                    useCenter = false,
                    topLeft = at,
                    size = arc,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            },
        contentAlignment = Alignment.Center,
    ) { Text("OK", style = hintStyle(FontWeight.Bold, RING_OK_SP), color = GlacierColors.Ice) }
}

/** The hints' text: a soft dark shadow instead of a box behind it. */
@Composable
private fun hintStyle(weight: FontWeight, size: Int) = GlacierText.body(size, weight).copy(
    shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f), blurRadius = 8f),
)

private const val HINT_GAP = 34
private const val HINT_TEXT_SP = 20
private const val HINT_OK_SP = 17
private const val RING_OK_SP = 13
private const val HINT_ICON = 22
private const val RING_SIZE = 34
private const val RING_STROKE = 2
private const val RING_MS = 900

/**
 * The whole photo, as large as the screen allows. Until it is sharp its BlurHash shows with a loader, tiny and drawn
 * smoothed up over exactly the area the photo will fill. A photo without a usable BlurHash shows its thumbnail instead
 * (decoded tiny and drawn smoothed up, which blurs it on every Android version); when neither is there, [previous]
 * stays dimmed. A photo that is in the memory cache comes without any of it. A line and a retry hint show when it
 * does not load.
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
    // The placeholder is decoded off the main thread; a neighbour decoded ahead is in the cache already.
    val hasPlaceholder = photo.hasPlaceholder()
    var placeholder by remember(photo.id) { mutableStateOf(photo.cachedPlaceholder()) }
    LaunchedEffect(photo.id) {
        if (hasPlaceholder && placeholder == null) {
            placeholder = withContext(Dispatchers.Default) { photo.placeholder() }
        }
    }
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
                if (!hasPlaceholder) {
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
                } else {
                    // Sized by the photo's shape, so it covers where the photo will be. The tiny bitmap's whole
                    // pixels round its shape a little, so it is stretched to the box instead of fitted into it.
                    placeholder?.let {
                        Image(
                            bitmap = it,
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            filterQuality = FilterQuality.Low,
                            modifier = Modifier.aspectRatio(photo.aspectRatio ?: 1f),
                        )
                    }
                }
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

/** The blurred thumbnail of a photo without a BlurHash: decoded to this many pixels on its long side, then drawn up. */
private const val BLUR_PX = 48

/** How many photos ahead in the browsing direction, and behind it, load before they are asked for. */
private const val PRELOAD_AHEAD = 3
private const val PRELOAD_BEHIND = 1
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
