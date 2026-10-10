package io.github.glacier_jellyfin.androidtv.favorites

import io.github.glacier_jellyfin.androidtv.core.data.settings.FavoriteRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.Favorites
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.ContinueCard
import io.github.glacier_jellyfin.androidtv.ui.LocalLibraryKinds
import io.github.glacier_jellyfin.androidtv.ui.MediaRow
import io.github.glacier_jellyfin.androidtv.ui.NavTarget
import io.github.glacier_jellyfin.androidtv.ui.PageEdge
import io.github.glacier_jellyfin.androidtv.ui.PosterCard
import io.github.glacier_jellyfin.androidtv.ui.TopNav
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.glacier_jellyfin.androidtv.ui.showsLock
import io.github.glacier_jellyfin.androidtv.ui.yearText

/** Everything marked with the heart: a row per kind, empty kinds left out. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FavoritesScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)
    val listFocus = remember { FocusRequester() }
    // Back from a title lands on its card again; the first time on the first card.
    var lastFocus by rememberSaveable { mutableStateOf<String?>(null) }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun Modifier.remembered(key: String): Modifier = this
        .focusRequester(requesters.getOrPut(key) { FocusRequester() })
        .onFocusChanged { if (it.isFocused) lastFocus = key }
    val favorites = state.favorites
    LaunchedEffect(favorites != null) {
        if (favorites == null || rows.none { favorites.has(it) }) return@LaunchedEffect
        withFrameNanos { }
        val restored = lastFocus?.let { requesters[it] }?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true
        if (!restored) runCatching { listFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            state.loading && favorites == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SpinningDiamond(110) }
            state.failed && favorites == null -> Message(
                text = stringResource(R.string.favorites_error),
                button = stringResource(R.string.action_retry),
                onClick = viewModel::load,
            )
            favorites == null -> Unit
            rows.none { favorites.has(it) } -> Message(text = stringResource(R.string.favorites_empty), body = stringResource(R.string.favorites_empty_body))
            else -> {
                val songsTitle = stringResource(R.string.favorites_songs)
                LazyColumn(Modifier.fillMaxSize().focusRequester(listFocus).focusRestorer()) {
                    item(key = "title") {
                        Text(
                            stringResource(R.string.nav_favorites),
                            style = GlacierText.display(50),
                            color = GlacierColors.Ice,
                            modifier = Modifier.padding(start = PageEdge.dp, top = 150.dp, bottom = 8.dp),
                        )
                    }
                    rows.filter { favorites.has(it) }.forEach { row ->
                        item(key = row.name) {
                            if (row == FavoriteRow.Songs) {
                                MediaRow(title = songsTitle) {
                                    items(favorites.songs, key = { it.id }) { track ->
                                        PosterCard(
                                            imageUrl = track.coverUrl,
                                            caption = track.artist.orEmpty(),
                                            title = track.title,
                                            square = true,
                                            onClick = { viewModel.play(track, songsTitle) },
                                            modifier = Modifier.remembered(track.id.toString()),
                                        )
                                    }
                                }
                            } else {
                                MediaRow(title = stringResource(row.title)) {
                                    items(favorites.titles(row), key = { it.id }) { item ->
                                        FavoriteCard(item, onClick = { viewModel.open(item) }, modifier = Modifier.remembered(item.id.toString()))
                                    }
                                }
                            }
                        }
                    }
                    // Room below the last row.
                    item(key = "end") { Spacer(Modifier.height(64.dp)) }
                }
            }
        }

        TopNav(
            active = NavTarget.Favorites,
            kinds = LocalLibraryKinds.current,
            userName = state.userName,
            onSelect = viewModel::onNav,
            down = listFocus,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 34.dp),
        )
    }
}

/** Heading of a row on the favorites page, also its name in Settings. */
@get:StringRes
val FavoriteRow.title: Int
    get() = when (this) {
        FavoriteRow.Movies -> R.string.library_movies
        FavoriteRow.Shows -> R.string.library_shows
        FavoriteRow.Episodes -> R.string.favorites_episodes
        FavoriteRow.Albums -> R.string.scope_albums
        FavoriteRow.Artists -> R.string.scope_artists
        FavoriteRow.Songs -> R.string.favorites_songs
    }

/** The titles of a row; songs are [Favorites.songs]. */
private fun Favorites.titles(row: FavoriteRow): List<MediaItem> = when (row) {
    FavoriteRow.Movies -> movies
    FavoriteRow.Shows -> shows
    FavoriteRow.Episodes -> episodes
    FavoriteRow.Albums -> albums
    FavoriteRow.Artists -> artists
    FavoriteRow.Songs -> emptyList()
}

private fun Favorites.has(row: FavoriteRow): Boolean = if (row == FavoriteRow.Songs) songs.isNotEmpty() else titles(row).isNotEmpty()

@Composable
private fun FavoriteCard(item: MediaItem, onClick: () -> Unit, modifier: Modifier) {
    when (item.kind) {
        // An episode shows its still, under the show's name, like "Continue watching".
        ItemKind.Episode -> ContinueCard(
            title = item.parentTitle ?: item.title,
            subtitle = episodeLine(item),
            imageUrl = item.thumbUrl ?: item.showThumbUrl ?: item.posterUrl,
            progress = item.progress,
            onClick = onClick,
            modifier = modifier,
            locked = item.showsLock(),
        )
        ItemKind.Album, ItemKind.Artist -> PosterCard(
            imageUrl = item.posterUrl,
            caption = item.parentTitle.orEmpty(),
            title = item.title,
            square = true,
            onClick = onClick,
            modifier = modifier,
        )
        else -> PosterCard(
            imageUrl = item.posterUrl,
            caption = listOfNotNull(yearText(item), item.genres.firstOrNull()).joinToString(" · "),
            title = item.title,
            badge = item.unwatchedCount,
            locked = item.showsLock(),
            onClick = onClick,
            modifier = modifier,
        )
    }
}

@Composable
private fun episodeLine(item: MediaItem): String {
    val season = item.seasonNumber
    val episode = item.episodeNumber
    val number = if (season != null && episode != null) stringResource(R.string.episode_short, season, episode) else null
    return listOfNotNull(number, item.title).joinToString(" · ")
}

/** Nothing to show: a line, an explanation and maybe a button, in the middle of the page. */
@Composable
private fun Message(text: String, body: String? = null, button: String? = null, onClick: () -> Unit = {}) {
    val focus = remember { FocusRequester() }
    if (button != null) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Text(text, style = GlacierText.display(30), color = GlacierColors.Ice)
        if (body != null) Text(body, style = GlacierText.body(20), color = GlacierColors.Mist)
        if (button != null) PillButton(button, onClick = onClick, primary = true, modifier = Modifier.focusRequester(focus))
    }
}
