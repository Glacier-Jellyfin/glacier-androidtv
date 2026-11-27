package io.github.glacier_jellyfin.androidtv.activity

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.PersonProfile
import io.github.glacier_jellyfin.androidtv.core.designsystem.Avatar
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.core.designsystem.blockKeysWhile
import io.github.glacier_jellyfin.androidtv.parental.PinDialog
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.PageEdge
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import java.util.Date

private const val ROW_WATCHING = "watching"
private const val ROW_WATCHED = "watched"
private const val ROW_FAVORITES = "favorites"

/** What one person watched and favorited, in rows like the activity page. Back returns to the page. */
@Composable
fun ActivityPersonScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    onBack: () -> Unit,
    viewModel: ActivityPersonViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pinPrompt by viewModel.pin.prompt.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)
    BackHandler(onBack = onBack)

    val profile = state.profile
    val listState = rememberLazyListState()
    val focus = rememberActivityFocus()
    // Leaving the page moves the focus around on its way out, which must not count as the card to come back to.
    LifecycleResumeEffect(focus) { onPauseOrDispose { focus.freeze() } }
    val rows = listOfNotNull(
        ROW_WATCHING.takeIf { profile?.watching?.isNotEmpty() == true },
        ROW_WATCHED.takeIf { profile?.watched?.isNotEmpty() == true },
        ROW_FAVORITES.takeIf { profile?.favorites?.isNotEmpty() == true },
    )
    // Back from a title lands on its card again; the first time on the first card.
    // Keyed on the rows too: the page can stop loading before its rows arrive.
    LaunchedEffect(state.loading, rows.isNotEmpty()) {
        if (state.loading) return@LaunchedEffect
        if (rows.isNotEmpty()) focus.restore(listState, rows)
    }

    val watchingNow = stringResource(R.string.activity_watching_now)
    val watchedTitle = stringResource(R.string.activity_watched)
    val favoritesTitle = stringResource(R.string.nav_favorites)

    Box(Modifier.fillMaxSize().blockKeysWhile(state.loading, takeFocus = true)) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SpinningDiamond(110)
            }

            profile == null || rows.isEmpty() -> ActivityMessage(
                text = stringResource(R.string.activity_empty),
                body = stringResource(R.string.activity_empty_body),
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().focusRequester(focus.list).focusRestorer(),
            ) {
                item(key = "header") { ProfileHeader(profile) }
                titleRow(
                    ROW_WATCHING,
                    watchingNow,
                    profile.watching,
                    focus,
                ) { title, modifier ->
                    ActivityCard(
                        title,
                        avatars = false,
                        onClick = { viewModel.open(title) },
                        modifier = modifier,
                        subtitle = titleSubline(title, names = null, withDay = true),
                        progress = title.progress,
                    )
                }
                titleRow(
                    ROW_WATCHED,
                    watchedTitle,
                    profile.watched,
                    focus,
                ) { title, modifier ->
                    ActivityCard(
                        title,
                        avatars = false,
                        onClick = { viewModel.open(title) },
                        modifier = modifier,
                        // A show card reads its episode count, a single title its code and day.
                        subtitle = titleSubline(title, names = null, withDay = title.seriesId == null),
                    )
                }
                titleRow(
                    ROW_FAVORITES,
                    favoritesTitle,
                    profile.favorites,
                    focus,
                ) { title, modifier ->
                    ActivityCard(
                        title,
                        avatars = false,
                        onClick = { viewModel.open(title) },
                        modifier = modifier,
                        subtitle = titleSubline(title, names = null, withDay = true),
                    )
                }
                // Room below the last row.
                item(key = "end") { Spacer(Modifier.height(64.dp)) }
            }
        }

        pinPrompt?.let { PinDialog(it, onKey = viewModel.pin::key, onDismiss = viewModel.pin::dismiss) }
    }
}

/** The big avatar, the name and "Last active today, 20:14 · 14 watched · 5 favorites". */
@Composable
private fun ProfileHeader(profile: PersonProfile) {
    val context = LocalContext.current
    val time = remember(profile.person.lastActiveMs, context) {
        DateFormat.getTimeFormat(context).format(Date(profile.person.lastActiveMs))
    }
    val summary = listOfNotNull(
        stringResource(R.string.activity_last_active_at, relativeDay(profile.person.lastActiveMs), time),
        pluralStringResource(R.plurals.activity_count_watched, profile.watchedCount, profile.watchedCount)
            .takeIf { profile.watchedCount > 0 },
        pluralStringResource(R.plurals.activity_count_favorites, profile.favoriteCount, profile.favoriteCount)
            .takeIf { profile.favoriteCount > 0 },
    ).joinToString(" · ")
    Row(
        Modifier.padding(start = PageEdge.dp, end = PageEdge.dp, top = 150.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        Avatar(profile.person.user.toAvatar(), size = 180.dp)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(profile.person.user.name, style = GlacierText.display(50), color = GlacierColors.Ice)
            Text(summary, style = GlacierText.body(22), color = GlacierColors.Mist)
        }
    }
}
