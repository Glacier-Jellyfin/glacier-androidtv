package io.github.glacier_jellyfin.androidtv.seerr

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrDetails
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrMediaType
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrSeason
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrStatus
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.detail.CastCard
import io.github.glacier_jellyfin.androidtv.detail.Crumb
import io.github.glacier_jellyfin.androidtv.detail.ErrorState
import io.github.glacier_jellyfin.androidtv.detail.HEADER_REGION
import io.github.glacier_jellyfin.androidtv.detail.Overview
import io.github.glacier_jellyfin.androidtv.detail.ScrollingBackdrop
import io.github.glacier_jellyfin.androidtv.detail.Title
import io.github.glacier_jellyfin.androidtv.ui.ActionButton
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.FactBadge
import io.github.glacier_jellyfin.androidtv.ui.FilterChip
import io.github.glacier_jellyfin.androidtv.ui.LocalCardSizes
import io.github.glacier_jellyfin.androidtv.ui.MediaRow
import io.github.glacier_jellyfin.androidtv.ui.PageEdge
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.glacier_jellyfin.androidtv.ui.ratingText
import io.github.glacier_jellyfin.androidtv.ui.rememberRowPivotSpec
import io.github.glacier_jellyfin.androidtv.ui.runtimeText
import io.github.glacier_jellyfin.androidtv.ui.seasonLabel
import io.github.glacier_jellyfin.androidtv.ui.ImagePageGround

private const val BACKDROP = 760

/** A movie or show that is not in the library yet, with what it takes to request it through Seerr. */
@Composable
fun SeerrScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    viewModel: SeerrViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)
    val details = state.details
    when {
        details == null && state.failed -> ErrorState(onRetry = viewModel::load)
        details == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SpinningDiamond(110) }
        else -> Page(state, details, viewModel)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Page(state: SeerrState, details: SeerrDetails, viewModel: SeerrViewModel) {
    val item = details.item
    val requestFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize().background(ImagePageGround)) {
        ScrollingBackdrop(item.backdropUrl, BACKDROP, listState)
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberRowPivotSpec(listState, HEADER_REGION)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                item(key = "header") {
                    Column(
                        Modifier.padding(start = PageEdge.dp, end = PageEdge.dp, top = 130.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        Crumb(stringResource(R.string.seerr_crumb, stringResource(if (item.type == SeerrMediaType.Movie) R.string.seerr_movie else R.string.seerr_show)))
                        Title(item.title, size = 84, maxWidth = 1180)
                        Facts(details)
                        item.overview?.let { Overview(it, maxWidth = 900) }
                        if (item.type == SeerrMediaType.Tv && details.seasons.isNotEmpty()) {
                            Seasons(details.seasons, state.selected, onToggle = viewModel::toggleSeason)
                        }
                        if (details.requestable) {
                            RequestInfo(state)
                            val label = if (item.type == SeerrMediaType.Movie) {
                                stringResource(R.string.seerr_request)
                            } else {
                                pluralStringResource(R.plurals.seerr_request_seasons, state.selected.size, state.selected.size)
                            }
                            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                PillButton(
                                    text = label,
                                    onClick = viewModel::request,
                                    primary = true,
                                    icon = GlacierIcons.Plus,
                                    // Not disabled while sending: that would drop the focus. The view model ignores repeats.
                                    enabled = item.type == SeerrMediaType.Movie || state.selected.isNotEmpty(),
                                    modifier = Modifier.focusRequester(requestFocus),
                                )
                                if (details.trailers.isNotEmpty()) TrailerButton(viewModel::playTrailer)
                            }
                            LaunchedEffect(item.tmdbId) {
                                withFrameNanos { }
                                runCatching { requestFocus.requestFocus() }
                            }
                        } else {
                            // Nothing left to ask for: the button stays where it was, showing where the request stands.
                            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                PillButton(
                                    text = stringResource(seerrStatusLabel(settledStatus(details))),
                                    onClick = {},
                                    primary = true,
                                    icon = if (details.item.status == SeerrStatus.Available) GlacierIcons.Check else null,
                                    enabled = false,
                                )
                                if (details.trailers.isNotEmpty()) {
                                    TrailerButton(viewModel::playTrailer, Modifier.focusRequester(requestFocus))
                                    // Also once a request went out and its button turned into the status.
                                    LaunchedEffect(item.tmdbId, details.requestable) {
                                        withFrameNanos { }
                                        runCatching { requestFocus.requestFocus() }
                                    }
                                }
                            }
                        }
                    }
                }
                if (details.cast.isNotEmpty()) {
                    item(key = "cast") {
                        MediaRow(
                            title = stringResource(R.string.detail_cast),
                            gap = LocalCardSizes.current.castGap,
                            bottomPadding = 120,
                            modifier = Modifier.padding(top = 28.dp),
                        ) {
                            items(details.cast) { person ->
                                CastCard(person.name, person.role, person.imageUrl, onClick = { viewModel.openPerson(person.name) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Facts(details: SeerrDetails) {
    val accent = LocalAccent.current.main
    val facts = listOfNotNull(
        details.item.year?.toString(),
        details.runtimeMinutes?.takeIf { details.item.type == SeerrMediaType.Movie }?.let { runtimeText(it) },
        details.seasons.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.seerr_seasons, it, it) },
    ) + details.genres.take(2)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        details.rating?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(GlacierIcons.Star, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Text(ratingText(it), style = GlacierText.body(20, FontWeight.Bold), color = accent)
            }
        }
        if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), style = GlacierText.body(20), color = GlacierColors.Mist)
        // Once nothing is left to request, the button shows the state instead.
        if (details.requestable && details.item.status != SeerrStatus.Unknown) {
            FactBadge(stringResource(seerrStatusLabel(details.item.status)), GlacierColors.GlassBorder2, 15, GlacierColors.Ice)
        }
    }
}

/** Seasons to tick for the request; those already there or asked for show their state instead. */
@Composable
private fun Seasons(seasons: List<SeerrSeason>, selected: Set<Int>, onToggle: (Int) -> Unit) {
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            stringResource(R.string.seerr_seasons_title).uppercase(),
            style = GlacierText.body(16).copy(letterSpacing = 0.08.em),
            color = GlacierColors.Mist,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            // Room for the focus scale at the start.
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            items(seasons, key = { it.number }) { season ->
                val label = if (season.requestable) {
                    seasonLabel(season.number)
                } else {
                    val state = if (season.requested && season.status in setOf(SeerrStatus.Unknown, SeerrStatus.Deleted)) SeerrStatus.Pending else season.status
                    "${seasonLabel(season.number)} · ${stringResource(seerrStatusLabel(state))}"
                }
                FilterChip(
                    label = label,
                    active = season.requestable && season.number in selected,
                    onClick = { if (season.requestable) onToggle(season.number) },
                    height = 50,
                    fontSize = 18,
                )
            }
        }
    }
}

@Composable
private fun TrailerButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ActionButton(onClick = onClick, modifier = modifier, label = stringResource(R.string.detail_trailer), icon = GlacierIcons.Play)
}

/** How many requests are left, and what happens after one is sent. */
@Composable
private fun RequestInfo(state: SeerrState) {
    Column(Modifier.padding(top = 10.dp).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.quota?.let { quota ->
            InfoLine(
                stringResource(R.string.seerr_quota_label),
                pluralStringResource(R.plurals.seerr_quota, quota.days, quota.remaining, quota.limit, quota.days),
            )
        }
        Text(
            stringResource(R.string.seerr_request_hint),
            style = GlacierText.body(18).copy(lineHeight = 27.sp),
            color = GlacierColors.Mist,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(label, style = GlacierText.body(19), color = GlacierColors.Mist, modifier = Modifier.width(220.dp))
        Text(value, style = GlacierText.body(19, FontWeight.SemiBold), color = GlacierColors.Ice, modifier = Modifier.weight(1f))
    }
}

/** Where a title stands once nothing is left to request; all seasons asked for counts as requested. */
private fun settledStatus(details: SeerrDetails): SeerrStatus =
    details.item.status.takeUnless { it == SeerrStatus.Unknown || it == SeerrStatus.Deleted } ?: SeerrStatus.Pending

/** The short state of a Seerr title, for cards and badges. */
@StringRes
fun seerrStatusLabel(status: SeerrStatus): Int = when (status) {
    SeerrStatus.Unknown, SeerrStatus.Deleted -> R.string.seerr_status_request
    SeerrStatus.Pending -> R.string.seerr_status_pending
    SeerrStatus.Processing -> R.string.seerr_status_processing
    SeerrStatus.PartiallyAvailable -> R.string.seerr_status_partial
    SeerrStatus.Available -> R.string.seerr_status_available
    SeerrStatus.Blocklisted -> R.string.seerr_status_blocked
}
