package io.github.glacier_jellyfin.androidtv.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.Library
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.ui.CardSize
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.PinDialog
import io.github.glacier_jellyfin.androidtv.ui.ContinueCard
import io.github.glacier_jellyfin.androidtv.ui.LibraryCard
import io.github.glacier_jellyfin.androidtv.ui.MediaRow
import io.github.glacier_jellyfin.androidtv.ui.NavTarget
import io.github.glacier_jellyfin.androidtv.ui.PosterCard
import io.github.glacier_jellyfin.androidtv.ui.TopNav
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.glacier_jellyfin.androidtv.ui.LocalToaster
import io.github.glacier_jellyfin.androidtv.update.UpdateDialog
import io.github.glacier_jellyfin.androidtv.update.UpdateViewModel
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateCandidate
import io.github.glacier_jellyfin.androidtv.ui.rememberCardPivotSpec
import io.github.glacier_jellyfin.androidtv.ui.rememberRowPivotSpec
import kotlinx.coroutines.delay


@OptIn(ExperimentalFoundationApi::class) // LocalBringIntoViewSpec
@Composable
fun HomeScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    updates: UpdateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)

    val pinPrompt by viewModel.pin.prompt.collectAsStateWithLifecycle()
    val playFocus = remember { FocusRequester() }
    // Saved: coming back from a detail page shows the same title again.
    var spotIndex by rememberSaveable { mutableIntStateOf(0) }
    val listFocus = remember { FocusRequester() }
    var spotlightFocused by remember { mutableStateOf(false) }
    val spotlight = state.spotlight

    // Rotate the spotlight, pausing while focus is inside it; a manual pick restarts the timer.
    LaunchedEffect(spotlight.size, spotlightFocused, spotIndex, state.settings.spotlightRotation.seconds) {
        val seconds = state.settings.spotlightRotation.seconds
        if (spotlight.size < 2 || spotlightFocused || seconds <= 0) return@LaunchedEffect
        delay(seconds * 1000L)
        spotIndex = (spotIndex + 1) % spotlight.size
    }
    // Focus "Play" once, when the spotlight first appears (not when returning later).
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }
    // Back from another page the screen is composed anew; focus goes back to the element the
    // user left from (the lists keep their scroll), not to the nav on top.
    var lastFocus by rememberSaveable { mutableStateOf<String?>(null) }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun Modifier.remembered(key: String): Modifier = this
        .focusRequester(requesters.getOrPut(key) { FocusRequester() })
        .onFocusChanged { if (it.isFocused) lastFocus = key }
    val hasContent = state.content != null
    LaunchedEffect(hasContent) {
        if (!hasContent || !initialFocusDone) return@LaunchedEffect
        withFrameNanos { }
        val restored = lastFocus?.let { requesters[it] }?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true
        if (!restored) runCatching { listFocus.requestFocus() }
    }
    // A dismissed PIN dialog gives focus back to the card that asked for it.
    var pinWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(pinPrompt == null) {
        if (pinPrompt == null && pinWasOpen) {
            withFrameNanos { }
            lastFocus?.let { requesters[it] }?.let { runCatching { it.requestFocus() } }
        }
        pinWasOpen = pinPrompt != null
    }

    // A new version found at start: shown once, a moment after the home screen has settled.
    val updatePrompt by updates.updates.prompt.collectAsStateWithLifecycle()
    var updateShown by remember { mutableStateOf<UpdateCandidate?>(null) }
    val toaster = LocalToaster.current
    val laterText = stringResource(R.string.update_later_toast)
    LaunchedEffect(updatePrompt, hasContent, pinPrompt == null) {
        val candidate = updatePrompt ?: return@LaunchedEffect
        if (!hasContent || pinPrompt != null) return@LaunchedEffect
        delay(1200)
        updateShown = candidate
        updates.updates.promptShown()
    }
    var updateWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(updateShown == null) {
        if (updateShown == null && updateWasOpen) {
            withFrameNanos { }
            val restored = lastFocus?.let { requesters[it] }?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true
            if (!restored) runCatching { listFocus.requestFocus() }
        }
        updateWasOpen = updateShown != null
    }

    Box(Modifier.fillMaxSize()) {
        val content = state.content
        when {
            state.loading && content == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SpinningDiamond(110) }
            state.failed && content == null -> ErrorState(onRetry = viewModel::load)
            content != null -> {
                val listState = rememberLazyListState()
                CompositionLocalProvider(LocalBringIntoViewSpec provides rememberRowPivotSpec(listState, SpotlightHeight)) {
                    LazyColumn(
                        state = listState,
                        // Down from the nav returns to the last focused card.
                        modifier = Modifier.fillMaxSize().focusRequester(listFocus).focusRestorer(),
                    ) {
                        item(key = "spotlight") {
                            if (spotlight.isNotEmpty()) {
                                Spotlight(
                                    items = spotlight,
                                    index = spotIndex.coerceIn(0, spotlight.lastIndex),
                                    onSelect = { spotIndex = it },
                                    onPlay = viewModel::play,
                                    onInfo = viewModel::openDetails,
                                    onFavorite = viewModel::toggleFavorite,
                                    playFocus = playFocus,
                                    modifier = Modifier.onFocusChanged { spotlightFocused = it.hasFocus },
                                    buttonModifier = { Modifier.remembered("spotlight-$it") },
                                )
                                LaunchedEffect(Unit) {
                                    if (!initialFocusDone) {
                                        // Wait one frame so the button is attached before focusing it.
                                        withFrameNanos { }
                                        initialFocusDone = runCatching { playFocus.requestFocus() }.isSuccess
                                    }
                                }
                            } else {
                                Box(Modifier.padding(top = 160.dp))
                            }
                        }
                        if (content.continueWatching.isNotEmpty()) {
                            item(key = "continue") {
                                MediaRow(title = stringResource(R.string.home_continue_watching)) {
                                    items(content.continueWatching, key = { it.id }) { item ->
                                        ContinueCard(
                                            title = if (item.kind == ItemKind.Episode) item.parentTitle ?: item.title else item.title,
                                            subtitle = continueSubtitle(item),
                                            // The show's art, not the episode still (user decision).
                                            imageUrl = item.showThumbUrl ?: item.posterUrl,
                                            progress = item.progress,
                                            onClick = { viewModel.openContinueWatching(item) },
                                            modifier = Modifier.remembered("continue-${item.id}"),
                                        )
                                    }
                                }
                            }
                        }
                        content.latest.forEach { (library, items) ->
                            item(key = "latest-${library.id}") {
                                MediaRow(
                                    title = stringResource(R.string.home_new_in, library.name),
                                    subtitle = libraryCount(library),
                                ) {
                                    items(items, key = { it.id }) { item ->
                                        PosterFor(item, onClick = { viewModel.openDetails(item) }, modifier = Modifier.remembered("latest-${library.id}-${item.id}"))
                                    }
                                }
                            }
                        }
                        if (content.libraries.isNotEmpty()) {
                            item(key = "libraries") {
                                MediaRow(title = stringResource(R.string.home_my_media), bottomPadding = 90) {
                                    items(content.libraries, key = { it.id }) { library ->
                                        // Jellyfin's generated library images carry the name already;
                                        // a backdrop from the library reads like the design's photo.
                                        val newest = content.latest.firstOrNull { it.first.id == library.id }?.second?.firstOrNull()
                                        LibraryCard(
                                            name = library.name,
                                            count = libraryCount(library),
                                            imageUrl = newest?.backdropUrl ?: newest?.posterUrl ?: library.imageUrl,
                                            onClick = { viewModel.openLibrary(library) },
                                            modifier = Modifier.remembered("library-${library.id}"),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        TopNav(
            active = NavTarget.Home,
            kinds = state.kinds,
            userName = state.userName,
            onSelect = viewModel::onNav,
            down = listFocus,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 34.dp),
        )

        pinPrompt?.let { PinDialog(it, onKey = viewModel.pin::key, onDismiss = viewModel.pin::dismiss) }

        val installed = updates.updates.installed
        if (installed != null) updateShown?.let { candidate ->
            UpdateDialog(
                candidate,
                installed,
                onNow = {
                    updateShown = null
                    updates.updates.download(install = true)
                },
                onLater = {
                    updateShown = null
                    toaster.show(laterText)
                },
            )
        }
    }
}

@Composable
private fun PosterFor(item: MediaItem, onClick: () -> Unit, modifier: Modifier) {
    if (item.kind == ItemKind.Album) {
        PosterCard(
            imageUrl = item.posterUrl,
            caption = item.parentTitle.orEmpty(),
            title = item.title,
            square = true,
            onClick = onClick,
            modifier = modifier,
        )
    } else {
        PosterCard(
            imageUrl = item.posterUrl,
            // Title above year and genre, like the album cards.
            caption = listOfNotNull(item.year?.toString(), item.genres.firstOrNull()).joinToString(" · "),
            title = item.title,
            badge = item.unwatchedCount,
            onClick = onClick,
            modifier = modifier,
        )
    }
}

@Composable
private fun continueSubtitle(item: MediaItem): String {
    val season = item.seasonNumber
    val episode = item.episodeNumber
    val left = item.remainingMinutes
    return when {
        item.kind == ItemKind.Episode && season != null && episode != null && left != null ->
            stringResource(R.string.episode_minutes_left, season, episode, left)
        item.kind == ItemKind.Episode && season != null && episode != null ->
            listOfNotNull(stringResource(R.string.episode_short, season, episode), item.title).joinToString(" · ")
        left != null -> stringResource(R.string.minutes_left, left)
        else -> item.year?.toString().orEmpty()
    }
}

@Composable
private fun libraryCount(library: Library): String? {
    val count = library.itemCount ?: return null
    return if (library.kind == LibraryKind.Music) {
        pluralStringResource(R.plurals.count_albums, count, count)
    } else {
        pluralStringResource(R.plurals.count_titles, count, count)
    }
}

@Composable
private fun ErrorState(onRetry: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.home_error), style = GlacierText.display(30), color = GlacierColors.Ice)
        PillButton(stringResource(R.string.action_retry), onClick = onRetry, primary = true, modifier = Modifier.focusRequester(focus))
    }
}
