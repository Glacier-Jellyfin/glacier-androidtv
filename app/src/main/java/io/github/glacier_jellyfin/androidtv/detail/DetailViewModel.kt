package io.github.glacier_jellyfin.androidtv.detail

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.ParentalControl
import io.github.glacier_jellyfin.androidtv.core.data.media.AgeFilter
import io.github.glacier_jellyfin.androidtv.core.data.media.CastMember
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemDetails
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.MusicRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.MusicShuffle
import io.github.glacier_jellyfin.androidtv.core.data.media.MusicTrack
import io.github.glacier_jellyfin.androidtv.core.data.media.Season
import io.github.glacier_jellyfin.androidtv.core.data.media.TrackSelection
import io.github.glacier_jellyfin.androidtv.core.data.media.TrackSelections
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackSource
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.navigation.DetailRoute
import io.github.glacier_jellyfin.androidtv.navigation.MusicRoute
import io.github.glacier_jellyfin.androidtv.navigation.PersonRoute
import io.github.glacier_jellyfin.androidtv.navigation.PlayerRoute
import io.github.glacier_jellyfin.androidtv.navigation.TrailerRoute
import io.github.glacier_jellyfin.androidtv.ui.PinGate
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class TrackKind { Audio, Subtitles }

data class DetailState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val details: ItemDetails? = null,
    /** Shows: all seasons and the one whose episodes are listed. */
    val seasons: List<Season> = emptyList(),
    val season: Season? = null,
    /** Episodes of [season] for shows; of the same season for an episode page. */
    val episodes: List<MediaItem> = emptyList(),
    /** Episode to focus in the episode row: the first unwatched one. */
    val focusEpisode: Int = 0,
    val similar: List<MediaItem> = emptyList(),
    /** Movies of a collection, in release order. */
    val collectionItems: List<MediaItem> = emptyList(),
    /** Next episode to play for a show. */
    val nextEpisode: MediaItem? = null,
    val selection: TrackSelection? = null,
    val trackPanel: TrackKind? = null,
    /** Songs of an album or playlist. */
    val musicTracks: List<MusicTrack> = emptyList(),
    /** Theme song to play behind a movie or show; cleared once it has played, so coming back stays quiet. */
    val themeSong: PlaybackSource? = null,
    /** Albums of an artist, newest first. */
    val artistAlbums: List<MediaItem> = emptyList(),
    val shuffle: Boolean = false,
    /** The title stays locked (PIN dialog dismissed, or hidden for this profile): leave the page. */
    val leave: Boolean = false,
) {
    val item: MediaItem? get() = details?.item
}

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DetailRepository,
    private val trackSelections: TrackSelections,
    private val music: MusicRepository,
    private val shuffle: MusicShuffle,
    private val playback: PlaybackRepository,
    private val settings: SettingsRepository,
    private val parental: ParentalControl,
    private val ageFilter: AgeFilter,
) : ViewModel() {

    private var itemId: UUID = UUID.fromString(savedStateHandle.toRoute<DetailRoute>().itemId)

    /** Theme song area known from the route, before the page has loaded. */
    val routeThemeArea: String? = savedStateHandle.toRoute<DetailRoute>().themeArea
    private var themeSongLoaded = false

    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val pin = PinGate(viewModelScope, parental) { _events.send(it) }

    init {
        load()
        viewModelScope.launch { shuffle.on.collect { on -> _state.update { it.copy(shuffle = on) } } }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            try {
                val details = repository.details(itemId)
                if (ageFilter.isLocked(itemId, listOfNotNull(details.seriesId))) {
                    askPin(details)
                    return@launch
                }
                val similarAsync = async { runCatching { repository.similar(itemId) }.getOrDefault(emptyList()) }
                _state.update {
                    it.copy(
                        details = details,
                        selection = trackSelections.get(itemId) ?: details.tracks?.let { t -> TrackSelection(t.defaultAudio, t.defaultSubtitle) },
                    )
                }
                when (details.item.kind) {
                    ItemKind.Series -> loadSeries(details)
                    ItemKind.Episode -> loadSeasonOf(details)
                    ItemKind.Collection -> loadCollection(details)
                    ItemKind.Album -> _state.update { it.copy(musicTracks = music.albumTracks(itemId)) }
                    ItemKind.Playlist -> _state.update { it.copy(musicTracks = music.playlistTracks(itemId)) }
                    ItemKind.Artist -> _state.update { it.copy(artistAlbums = music.artistAlbums(itemId)) }
                    else -> Unit
                }
                if (details.item.kind == ItemKind.Movie || details.item.kind == ItemKind.Series) loadThemeSong()
                val withSimilar = details.item.kind in setOf(ItemKind.Movie, ItemKind.Series, ItemKind.Album)
                _state.update { it.copy(loading = false, similar = if (withSimilar) similarAsync.await() else emptyList()) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading details failed", e)
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    /** The PIN reloads the page; without it the page is left. */
    private fun askPin(details: ItemDetails) {
        _state.update { it.copy(loading = false) }
        pin.openTitle(
            ageFilter,
            id = itemId,
            name = details.item.title,
            rating = details.item.officialRating,
            parents = listOfNotNull(details.seriesId),
            onRefused = ::leave,
            onOpen = ::load,
        )
    }

    fun dismissPin() {
        pin.dismiss()
        leave()
    }

    private fun leave() = _state.update { it.copy(leave = true) }

    private fun loadThemeSong() {
        if (themeSongLoaded || !settings.settings.value.playback.themeSongs) return
        themeSongLoaded = true
        viewModelScope.launch {
            runCatching { music.themeSong(itemId)?.let(playback::audioSource) }
                .onSuccess { source -> _state.update { it.copy(themeSong = source) } }
                .onFailure { Log.w(TAG, "Loading the theme song failed", it) }
        }
    }

    /** Design: open a show on the first season with something unwatched. */
    private suspend fun loadSeries(details: ItemDetails) {
        val seasons = repository.seasons(details.item.id)
        val season = seasons.firstOrNull { (it.unwatchedCount ?: 0) > 0 } ?: seasons.lastOrNull()
        val next = runCatching { repository.nextEpisode(details.item.id) }.getOrNull()
        _state.update { it.copy(seasons = seasons, nextEpisode = next) }
        season?.let { selectSeason(it, details.item.id) }
    }

    private suspend fun loadSeasonOf(details: ItemDetails) {
        val seriesId = details.seriesId ?: return
        val seasonId = details.seasonId ?: return
        val episodes = repository.episodes(seriesId, seasonId)
        _state.update { state ->
            state.copy(
                episodes = episodes,
                season = Season(seasonId, details.item.seasonNumber, "", null),
                focusEpisode = episodes.indexOfFirst { it.id == details.item.id }.coerceAtLeast(0),
            )
        }
    }

    private suspend fun loadCollection(details: ItemDetails) {
        val items = repository.collectionItems(details.item.id)
        // The PIN given for the collection counts for its movies too.
        if (details.item.id.toString() in parental.unlockedItems.value) items.forEach { parental.unlock(it.id.toString()) }
        _state.update { it.copy(collectionItems = items) }
    }

    /** Design: a collection plays the movie in progress, else the first one not yet watched. */
    fun collectionNext(): MediaItem? {
        val items = _state.value.collectionItems
        return items.firstOrNull { it.progress != null } ?: items.firstOrNull { !it.played } ?: items.firstOrNull()
    }

    fun selectSeason(season: Season) {
        val seriesId = _state.value.item?.id ?: return
        viewModelScope.launch { selectSeason(season, seriesId) }
    }

    private suspend fun selectSeason(season: Season, seriesId: UUID) {
        _state.update { it.copy(season = season) }
        val episodes = runCatching { repository.episodes(seriesId, season.id) }
            .onFailure { Log.w(TAG, "Loading episodes failed", it) }
            .getOrDefault(emptyList())
        val firstUnwatched = episodes.indexOfFirst { !it.played }.takeIf { it >= 0 } ?: 0
        _state.update { if (it.season?.id == season.id) it.copy(episodes = episodes, focusEpisode = firstUnwatched) else it }
    }

    /** Episode pages switch in place when another episode of the season is picked (design). */
    fun showEpisode(episode: MediaItem) {
        if (episode.id == itemId) return
        itemId = episode.id
        load()
    }

    fun play(fromStart: Boolean = false) {
        val current = _state.value
        val item = current.item ?: return
        val target = when (item.kind) {
            ItemKind.Series -> current.nextEpisode ?: current.episodes.firstOrNull() ?: return
            ItemKind.Collection -> collectionNext() ?: return
            else -> item
        }
        navigate(PlayerRoute(target.id.toString(), fromStart))
    }

    /** Album, artist or playlist in the music player, from [track] or from the start. */
    fun playMusic(track: MusicTrack? = null) {
        val item = _state.value.item ?: return
        navigate(MusicRoute(item.id.toString(), track?.id?.toString()))
    }

    fun toggleShuffle() {
        val on = !shuffle.on.value
        shuffle.set(on)
        toast(if (on) R.string.shuffle_on else R.string.shuffle_off)
    }

    fun playTrailer() {
        val item = _state.value.item ?: return
        navigate(TrailerRoute(item.id.toString()))
    }

    fun toggleFavorite() {
        val item = _state.value.item ?: return
        val favorite = !item.isFavorite
        updateItem { it.copy(isFavorite = favorite) }
        viewModelScope.launch {
            runCatching { repository.setFavorite(item.id, favorite) }
                .onSuccess { toast(if (favorite) R.string.favorite_added else R.string.favorite_removed) }
                .onFailure { updateItem { current -> current.copy(isFavorite = !favorite) } }
        }
    }

    fun togglePlayed() {
        val item = _state.value.item ?: return
        val played = !item.played
        // The server drops the resume point both ways; the resume button and the episode row follow at once.
        val marked: (MediaItem) -> MediaItem = { it.copy(played = played, progress = null, remainingMinutes = null, resumePositionMs = 0) }
        updateItem(marked)
        _state.update { state -> state.copy(episodes = state.episodes.map { if (it.id == item.id) marked(it) else it }) }
        viewModelScope.launch {
            runCatching { repository.setPlayed(item.id, played) }
                .onSuccess {
                    toast(
                        when {
                            item.kind == ItemKind.Album && played -> R.string.marked_heard
                            item.kind == ItemKind.Album -> R.string.marked_unheard
                            played -> R.string.marked_watched
                            else -> R.string.marked_unwatched
                        },
                    )
                    // A whole show or collection changes its episodes or movies too.
                    if (item.kind == ItemKind.Series) {
                        _state.value.season?.let { selectSeason(it, item.id) }
                        val next = runCatching { repository.nextEpisode(item.id) }.getOrNull()
                        _state.update { it.copy(nextEpisode = next) }
                    }
                    if (item.kind == ItemKind.Collection) loadCollection(_state.value.details ?: return@onSuccess)
                }
                .onFailure {
                    updateItem { item }
                    _state.update { state -> state.copy(episodes = state.episodes.map { if (it.id == item.id) item else it }) }
                }
        }
    }

    fun openTracks(kind: TrackKind) = _state.update { it.copy(trackPanel = kind) }

    fun closeTracks() = _state.update { it.copy(trackPanel = null) }

    fun pickTrack(kind: TrackKind, index: Int?, label: String) {
        val current = _state.value.selection ?: TrackSelection(null, null)
        val selection = if (kind == TrackKind.Audio) current.copy(audio = index) else current.copy(subtitle = index)
        trackSelections.set(itemId, selection)
        _state.update { it.copy(selection = selection, trackPanel = null) }
        viewModelScope.launch {
            _events.send(UiEvent.Toast(if (kind == TrackKind.Audio) R.string.track_audio_set else R.string.track_subtitles_set, listOf(label)))
        }
    }

    fun openEpisode(episode: MediaItem) = navigate(DetailRoute(episode.id.toString(), themeArea = episode.seriesId?.toString()))

    fun openItem(item: MediaItem) = navigate(DetailRoute(item.id.toString()))

    fun openPerson(person: CastMember) =
        navigate(PersonRoute(person.id.toString(), fromTitle = _state.value.item?.title, role = person.role))

    private fun updateItem(change: (MediaItem) -> MediaItem) = _state.update { state ->
        state.copy(details = state.details?.let { it.copy(item = change(it.item)) })
    }

    private fun toast(message: Int) {
        viewModelScope.launch { _events.send(UiEvent.Toast(message)) }
    }

    private fun navigate(route: Any) {
        viewModelScope.launch { _events.send(UiEvent.Navigate(route)) }
    }

    private companion object {
        const val TAG = "Detail"
    }
}
