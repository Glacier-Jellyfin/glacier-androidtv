package io.github.glacier_jellyfin.androidtv.detail

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.Credit
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.PersonDetails
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.navigation.DetailRoute
import io.github.glacier_jellyfin.androidtv.navigation.PersonRoute
import io.github.glacier_jellyfin.androidtv.navigation.SearchRoute
import io.github.glacier_jellyfin.androidtv.ui.ActionButton
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.DetailBackdrop
import io.github.glacier_jellyfin.androidtv.ui.FactBadge
import io.github.glacier_jellyfin.androidtv.ui.GridCard
import io.github.glacier_jellyfin.androidtv.ui.MediaRow
import io.github.glacier_jellyfin.androidtv.ui.PageEdge
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.glacier_jellyfin.androidtv.ui.rememberRowPivotSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class PersonState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val person: PersonDetails? = null,
    /** Breadcrumb: title the person was opened from, and their role in it. */
    val fromTitle: String? = null,
    val role: String? = null,
)

@HiltViewModel
class PersonViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DetailRepository,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<PersonRoute>()
    private val _state = MutableStateFlow(PersonState(fromTitle = route.fromTitle, role = route.role))
    val state: StateFlow<PersonState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            try {
                val person = repository.person(UUID.fromString(route.personId))
                _state.update { it.copy(loading = false, person = person) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading person failed", e)
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    fun toggleFavorite() {
        val person = _state.value.person ?: return
        val favorite = !person.isFavorite
        _state.update { it.copy(person = person.copy(isFavorite = favorite)) }
        viewModelScope.launch {
            runCatching { repository.setFavorite(person.id, favorite) }
                .onSuccess { _events.send(UiEvent.Toast(if (favorite) R.string.favorite_added else R.string.favorite_removed)) }
                .onFailure { _state.update { it.copy(person = it.person?.copy(isFavorite = !favorite)) } }
        }
    }

    fun searchLibrary() {
        val name = _state.value.person?.name ?: return
        viewModelScope.launch { _events.send(UiEvent.Navigate(SearchRoute(query = name))) }
    }

    fun open(credit: Credit) {
        viewModelScope.launch { _events.send(UiEvent.Navigate(DetailRoute(credit.item.id.toString()))) }
    }

    private companion object {
        const val TAG = "Person"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PersonScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    viewModel: PersonViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)
    val person = state.person
    val listState = rememberLazyListState()
    val favoriteFocus = remember { FocusRequester() }

    Box(Modifier.fillMaxSize()) {
        when {
            person == null && state.failed -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
            ) {
                Text(stringResource(R.string.detail_error), style = GlacierText.display(30), color = GlacierColors.Ice)
                PillButton(stringResource(R.string.action_retry), onClick = viewModel::load, primary = true)
            }
            person == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SpinningDiamond(110) }
            else -> {
                DetailBackdrop(
                    url = person.credits.firstOrNull { it.item.backdropUrl != null }?.item?.backdropUrl,
                    height = 700,
                    modifier = Modifier.graphicsLayer {
                        translationY = if (listState.firstVisibleItemIndex == 0) -listState.firstVisibleItemScrollOffset.toFloat() else -size.height
                    },
                )
                CompositionLocalProvider(LocalBringIntoViewSpec provides rememberRowPivotSpec(listState, 650)) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        item(key = "header") {
                            Row(Modifier.padding(start = PageEdge.dp, end = PageEdge.dp, top = 132.dp), horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                                Portrait(person)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                    val crumb = listOfNotNull(state.fromTitle, state.role).joinToString(" · ")
                                    if (crumb.isNotEmpty()) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Icon(GlacierIcons.ChevronLeft, contentDescription = null, tint = GlacierColors.Mist, modifier = Modifier.size(16.dp))
                                            Text(crumb.uppercase(), style = GlacierText.body(17).copy(letterSpacing = 0.06.em), color = GlacierColors.Mist)
                                        }
                                    }
                                    Text(
                                        person.name,
                                        style = GlacierText.display(76).copy(lineHeight = 79.sp, shadow = Shadow(Color(0x99000000), blurRadius = 30f)),
                                        color = GlacierColors.Ice,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    PersonFacts(person)
                                    person.biography?.let {
                                        Text(
                                            // Paragraph breaks would leave a lone ellipsis line when clamped.
                                            it.replace(Regex("""\s*\n\s*"""), " "),
                                            style = GlacierText.body(21).copy(lineHeight = 34.sp),
                                            color = GlacierColors.Ice,
                                            maxLines = 5,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 920.dp),
                                        )
                                    }
                                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        ActionButton(
                                            onClick = viewModel::toggleFavorite,
                                            icon = if (person.isFavorite) GlacierIcons.HeartFilled else GlacierIcons.Heart,
                                            on = person.isFavorite,
                                            contentDescription = stringResource(if (person.isFavorite) R.string.action_unfavorite else R.string.action_favorite),
                                            modifier = Modifier.focusRequester(favoriteFocus),
                                        )
                                        ActionButton(onClick = viewModel::searchLibrary, label = stringResource(R.string.person_search), icon = GlacierIcons.Search)
                                    }
                                }
                            }
                            LaunchedEffect(person.id) {
                                withFrameNanos { }
                                runCatching { favoriteFocus.requestFocus() }
                            }
                        }
                        if (person.credits.isNotEmpty()) {
                            item(key = "filmography") {
                                MediaRow(
                                    title = stringResource(R.string.person_filmography),
                                    subtitle = stringResource(R.string.person_by_year),
                                    bottomPadding = 120,
                                    modifier = Modifier.padding(top = 46.dp),
                                ) {
                                    items(person.credits, key = { it.item.id }) { credit ->
                                        Box {
                                            GridCard(
                                                imageUrl = credit.item.posterUrl,
                                                title = credit.item.title,
                                                caption = listOfNotNull(credit.role, credit.item.year?.toString()).joinToString(" · "),
                                                onClick = { viewModel.open(credit) },
                                            )
                                            KindPill(if (credit.item.kind == ItemKind.Series) R.string.kind_show else R.string.kind_movie)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Portrait(person: PersonDetails) {
    val accent = LocalAccent.current
    Box(
        Modifier
            .size(300.dp)
            .clip(PillShape)
            .background(Brush.linearGradient(listOf(accent.deep, GlacierColors.Void)))
            .border(1.dp, GlacierColors.GlassBorder2, PillShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(person.name.take(1).uppercase(), style = GlacierText.display(88), color = GlacierColors.Ice)
        if (person.imageUrl != null) Artwork(person.imageUrl, Modifier.fillMaxSize())
    }
}

/** Role chip, birth year, birthplace, titles in this library, favourite tag. */
@Composable
private fun PersonFacts(person: PersonDetails) {
    val accent = LocalAccent.current.main
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier
                .height(36.dp)
                .clip(PillShape)
                .background(accent.copy(alpha = 0.16f))
                .border(1.dp, accent.copy(alpha = 0.4f), PillShape)
                .padding(horizontal = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.detail_cast).uppercase(), style = GlacierText.body(15, FontWeight.Bold).copy(letterSpacing = 0.09.em), color = accent)
        }
        val count = person.credits.size
        val facts = listOfNotNull(
            person.born?.year?.let { stringResource(R.string.person_born, it.toString()) },
            person.birthplace,
            pluralStringResource(R.plurals.person_titles, count, count),
        )
        Text(facts.joinToString("  ·  "), style = GlacierText.body(20), color = GlacierColors.Mist)
        if (person.isFavorite) FactBadge(stringResource(R.string.favorite_tag), accent.copy(alpha = 0.45f), 16, accent)
    }
}

@Composable
private fun KindPill(label: Int) {
    Box(
        Modifier
            .padding(14.dp)
            .height(32.dp)
            .clip(PillShape)
            .background(GlacierColors.Void.copy(alpha = 0.75f))
            .border(1.dp, GlacierColors.GlassBorder2, PillShape)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(label), style = GlacierText.body(15, FontWeight.Bold), color = GlacierColors.Ice)
    }
}
