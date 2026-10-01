package io.github.glacier_jellyfin.androidtv.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.KeyCap
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.core.designsystem.SystemTextInput
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.LocalLibraryKinds
import io.github.glacier_jellyfin.androidtv.ui.LocalCardSizes
import io.github.glacier_jellyfin.androidtv.ui.NavTarget
import io.github.glacier_jellyfin.androidtv.ui.PosterCard
import io.github.glacier_jellyfin.androidtv.ui.TopNav
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.glacier_jellyfin.androidtv.ui.showsLock

/** Design keypad: six columns of letters and digits, then keyboard, space and delete. */
private val KeypadRows = listOf("ABCDEF", "GHIJKL", "MNOPQR", "STUVWX", "YZ0123", "456789")
private const val KeyWidth = 94
private const val KeyHeight = 76
private const val KeyGap = 12
private const val Columns = 4

@Composable
fun SearchScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)
    val keyFocus = remember { FocusRequester() }
    var systemKeyboard by rememberSaveable { mutableStateOf(false) }
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!initialFocusDone) {
            withFrameNanos { }
            initialFocusDone = runCatching { keyFocus.requestFocus() }.isSuccess
        }
    }

    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 80.dp, end = 80.dp, top = 150.dp),
            horizontalArrangement = Arrangement.spacedBy(56.dp),
        ) {
            Column(Modifier.width(620.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                SearchField(state.query)
                Keypad(
                    query = state.query,
                    onQueryChange = viewModel::setQuery,
                    onSystemKeyboard = { systemKeyboard = true },
                    firstKeyFocus = keyFocus,
                )
            }
            Results(state, onOpen = viewModel::open, onRetry = viewModel::retry, modifier = Modifier.weight(1f))
        }

        TopNav(
            active = NavTarget.Search,
            kinds = LocalLibraryKinds.current,
            userName = state.userName,
            onSelect = viewModel::onNav,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 34.dp),
        )
    }

    SystemTextInput(
        text = state.query,
        onTextChange = viewModel::setQuery,
        open = systemKeyboard,
        onClose = {
            systemKeyboard = false
            keyFocus.requestFocus()
        },
    )
}

@Composable
private fun SearchField(query: String) {
    val shape = RoundedCornerShape(GlacierShapes.RadiusSm)
    Row(
        Modifier
            .width(620.dp)
            .height(78.dp)
            .clip(shape)
            .background(GlacierColors.GlassFill)
            .border(1.dp, GlacierColors.GlassBorder, shape)
            .padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(GlacierIcons.Search, contentDescription = null, tint = GlacierColors.Mist, modifier = Modifier.size(26.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (query.isNotEmpty()) {
                // Long queries keep their end visible, where typing happens.
                Text(
                    query,
                    style = GlacierText.body(26, FontWeight.SemiBold),
                    color = GlacierColors.Ice,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            // A static caret: a blinking one would keep the whole screen redrawing.
            Box(Modifier.width(2.dp).height(32.dp).background(LocalAccent.current.main))
            if (query.isEmpty()) {
                Text(
                    stringResource(R.string.search_placeholder),
                    style = GlacierText.body(26, FontWeight.SemiBold),
                    color = GlacierColors.Mist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Keypad(
    query: String,
    onQueryChange: (String) -> Unit,
    onSystemKeyboard: () -> Unit,
    firstKeyFocus: FocusRequester,
) {
    Column(verticalArrangement = Arrangement.spacedBy(KeyGap.dp)) {
        KeypadRows.forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KeyGap.dp)) {
                row.forEachIndexed { index, char ->
                    KeyCap(
                        onClick = { onQueryChange(typeChar(query, char)) },
                        width = KeyWidth,
                        height = KeyHeight,
                        modifier = if (rowIndex == 0 && index == 0) Modifier.focusRequester(firstKeyFocus) else Modifier,
                    ) { color -> Text(char.toString(), style = GlacierText.display(24), color = color) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KeyGap.dp)) {
            KeyCap(onClick = onSystemKeyboard, width = span(2), height = KeyHeight) { color ->
                Icon(GlacierIcons.Keyboard, contentDescription = stringResource(R.string.keyboard_system), tint = color, modifier = Modifier.size(30.dp))
            }
            KeyCap(onClick = { if (query.isNotEmpty() && !query.endsWith(' ')) onQueryChange("$query ") }, width = span(3), height = KeyHeight) { color ->
                Text(stringResource(R.string.keyboard_space), style = GlacierText.body(23, FontWeight.SemiBold), color = color)
            }
            KeyCap(onClick = { onQueryChange(query.dropLast(1)) }, width = KeyWidth, height = KeyHeight) { color ->
                Icon(GlacierIcons.Backspace, contentDescription = stringResource(R.string.keyboard_delete), tint = color, modifier = Modifier.size(26.dp))
            }
        }
    }
}

private fun span(keys: Int) = KeyWidth * keys + KeyGap * (keys - 1)

/** Keys show capitals; typed text is capitalised per word so it reads like a title. */
internal fun typeChar(query: String, char: Char): String {
    val wordStart = query.isEmpty() || query.last() == ' '
    return query + if (wordStart) char.uppercaseChar() else char.lowercaseChar()
}

@Composable
private fun Results(state: SearchState, onOpen: (MediaItem) -> Unit, onRetry: () -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(22.dp)) {
        val title = when {
            state.resultsFor == null -> stringResource(R.string.search_suggestions)
            else -> pluralStringResource(R.plurals.search_results, state.results.size, state.results.size, state.resultsFor)
        }
        Text(title, style = GlacierText.display(28), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
        when {
            state.failed -> Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Text(stringResource(R.string.search_error), style = GlacierText.body(21), color = GlacierColors.Mist)
                PillButton(stringResource(R.string.action_retry), onClick = onRetry)
            }
            state.loading && state.results.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { SpinningDiamond(60) }
            state.resultsFor != null && state.results.isEmpty() -> Text(
                stringResource(R.string.search_none, state.resultsFor),
                style = GlacierText.body(21),
                color = GlacierColors.Mist,
                modifier = Modifier.padding(vertical = 60.dp),
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(Columns),
                horizontalArrangement = Arrangement.spacedBy(26.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp),
                // Room for the focus ring and scale on the edges.
                contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 60.dp),
                modifier = Modifier.width((LocalCardSizes.current.posterWidth * Columns + 26 * (Columns - 1) + 24).dp),
            ) {
                items(state.results, key = { it.id }) { item -> ResultCard(item, onClick = { onOpen(item) }) }
            }
        }
    }
}

@Composable
private fun ResultCard(item: MediaItem, onClick: () -> Unit) {
    if (item.kind == ItemKind.Album) {
        // Design: albums show title and artist under a square cover.
        PosterCard(imageUrl = item.posterUrl, caption = item.parentTitle.orEmpty(), onClick = onClick, square = true, title = item.title)
    } else {
        // Title above the year, laid out like the album cards.
        PosterCard(imageUrl = item.posterUrl, caption = item.year?.toString().orEmpty(), onClick = onClick, title = item.title, locked = item.showsLock())
    }
}
