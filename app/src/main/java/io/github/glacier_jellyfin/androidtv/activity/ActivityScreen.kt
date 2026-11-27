package io.github.glacier_jellyfin.androidtv.activity

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityPerson
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.core.designsystem.blockKeysWhile
import io.github.glacier_jellyfin.androidtv.detail.CastCard
import io.github.glacier_jellyfin.androidtv.navigation.LocalLibraryKinds
import io.github.glacier_jellyfin.androidtv.navigation.NavTarget
import io.github.glacier_jellyfin.androidtv.navigation.TopNav
import io.github.glacier_jellyfin.androidtv.parental.PinDialog
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.LocalCardSizes
import io.github.glacier_jellyfin.androidtv.ui.MediaRow
import io.github.glacier_jellyfin.androidtv.ui.PageEdge
import io.github.glacier_jellyfin.androidtv.ui.UiEvent

private const val ROW_WATCHED = "watched"
private const val ROW_FAVORITED = "favorited"
private const val ROW_PEOPLE = "people"

/**
 * What others on the server watched and favorited, and who they are: stacked rows like the Favorites page,
 * under the navigation bar. Back among the rows goes up to the bar first.
 */
@Composable
fun ActivityScreen(onNavigate: (UiEvent.Navigate) -> Unit, viewModel: ActivityViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pinPrompt by viewModel.pin.prompt.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)
    // Back among the rows first goes up to the navigation bar; on the bar it goes home.
    val navFocus = remember { FocusRequester() }
    var inList by remember { mutableStateOf(false) }
    BackHandler(enabled = inList) { runCatching { navFocus.requestFocus() } }
    // A feed changed on the server shows after coming back to the page.
    LifecycleResumeEffect(Unit) {
        viewModel.resumed()
        onPauseOrDispose { }
    }

    val listState = rememberLazyListState()
    val focus = rememberActivityFocus()
    // Leaving the page moves the focus around on its way out, which must not count as the card to come back to.
    LifecycleResumeEffect(focus) { onPauseOrDispose { focus.freeze() } }
    val rows = listOfNotNull(
        ROW_WATCHED.takeIf { state.watched.isNotEmpty() },
        ROW_FAVORITED.takeIf { state.favorited.isNotEmpty() },
        ROW_PEOPLE.takeIf { state.people.isNotEmpty() },
    )
    // Back from a title or a profile lands on its card or avatar; the first time on the first card.
    // Keyed on the rows too: the page can stop loading before its rows arrive.
    LaunchedEffect(state.loading, rows.isNotEmpty()) {
        if (state.loading) return@LaunchedEffect
        if (rows.isNotEmpty()) focus.restore(listState, rows)
    }

    val watchedTitle = stringResource(R.string.activity_watched)
    val favoritedTitle = stringResource(R.string.activity_favorited)

    Box(Modifier.fillMaxSize().blockKeysWhile(state.loading, takeFocus = true)) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SpinningDiamond(110)
            }

            state.isEmpty -> ActivityMessage(
                text = stringResource(R.string.activity_empty),
                body = stringResource(R.string.activity_empty_body),
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { inList = it.hasFocus }
                    .focusRequester(focus.list)
                    .focusRestorer(),
            ) {
                item(key = "title") {
                    Text(
                        stringResource(R.string.activity_title),
                        style = GlacierText.display(50),
                        color = GlacierColors.Ice,
                        modifier = Modifier.padding(start = PageEdge.dp, top = 150.dp, bottom = 8.dp),
                    )
                }
                titleRow(
                    ROW_WATCHED,
                    watchedTitle,
                    state.watched,
                    focus,
                ) { title, modifier ->
                    ActivityCard(
                        title,
                        avatars = true,
                        onClick = { viewModel.open(title) },
                        modifier = modifier,
                        subtitle = titleSubline(title, title.users.firstOrNull()?.name, withDay = true),
                        progress = title.progress.takeUnless { title.completed },
                    )
                }
                titleRow(
                    ROW_FAVORITED,
                    favoritedTitle,
                    state.favorited,
                    focus,
                ) { title, modifier ->
                    ActivityCard(
                        title,
                        avatars = true,
                        onClick = { viewModel.open(title) },
                        modifier = modifier,
                        // One person reads "Nora · today", several just their names.
                        subtitle = titleSubline(
                            title,
                            names = title.users.joinToString(", ") { it.name },
                            withDay = title.users.size == 1,
                        ),
                    )
                }
                if (state.people.isNotEmpty()) {
                    item(key = ROW_PEOPLE) {
                        PeopleRow(state.people, focus, viewModel::openPerson)
                    }
                }
                // Room below the last row.
                item(key = "end") { Spacer(Modifier.height(64.dp)) }
            }
        }

        TopNav(
            active = NavTarget.Activity,
            kinds = LocalLibraryKinds.current,
            userName = state.userName,
            onSelect = viewModel::onNav,
            down = focus.list,
            focusRequester = navFocus,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 34.dp),
        )

        pinPrompt?.let { PinDialog(it, onKey = viewModel.pin::key, onDismiss = viewModel.pin::dismiss) }
    }
}

/** The people of the feed as round portraits like the cast of a title, the most recently active first. */
@Composable
private fun PeopleRow(
    people: List<ActivityPerson>,
    focus: ActivityFocus,
    onOpen: (ActivityPerson) -> Unit,
) {
    MediaRow(title = stringResource(R.string.activity_people), gap = LocalCardSizes.current.castGap) {
        items(people, key = { it.user.id }) { person ->
            CastCard(
                name = person.user.name,
                role = stringResource(R.string.activity_last_active, relativeDay(person.lastActiveMs)),
                imageUrl = person.user.imageUrl,
                onClick = { onOpen(person) },
                modifier = Modifier.remembered(focus, ROW_PEOPLE, person.user.id.toString()),
            )
        }
    }
}
