package io.github.glacier_jellyfin.androidtv.detail

import io.github.glacier_jellyfin.androidtv.core.log.Log
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
import io.github.glacier_jellyfin.androidtv.core.data.media.forVersion
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackSource
import io.github.glacier_jellyfin.androidtv.core.data.settings.RememberedTracks
import io.github.glacier_jellyfin.androidtv.core.data.settings.ServerPreferencesRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.closestTrack
import io.github.glacier_jellyfin.androidtv.core.data.settings.toKey
import io.github.glacier_jellyfin.androidtv.core.data.settings.rememberedTracks
import io.github.glacier_jellyfin.androidtv.core.data.settings.showKey
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.music.MusicActions
import io.github.glacier_jellyfin.androidtv.music.MusicController
import io.github.glacier_jellyfin.androidtv.music.MusicTarget
import io.github.glacier_jellyfin.androidtv.navigation.DetailRoute
import io.github.glacier_jellyfin.androidtv.navigation.MusicRoute
import io.github.glacier_jellyfin.androidtv.navigation.PersonRoute
import io.github.glacier_jellyfin.androidtv.navigation.PlayerRoute
import io.github.glacier_jellyfin.androidtv.navigation.TrailerRoute
import io.github.glacier_jellyfin.androidtv.player.VersionChooser
import io.github.glacier_jellyfin.androidtv.ui.PinGate
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.Job
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

enum class TrackKind { Audio, Subtitles, Version }

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
    /** Music videos of an artist. */
    val artistVideos: List<MediaItem> = emptyList(),
    /** Videos of a playlist (its songs are in [musicTracks]). */
    val playlistVideos: List<MediaItem> = emptyList(),
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
    private val serverPreferences: ServerPreferencesRepository,
    private val parental: ParentalControl,
    private val ageFilter: AgeFilter,
    private val musicPlayback: MusicController,
    private val versions: VersionChooser,
) : ViewModel() {

    private var itemId: UUID = UUID.fromString(savedStateHandle.toRoute<DetailRoute>().itemId)

    /** Theme song area known from the route, before the page has loaded. */
    val routeThemeArea: String? = savedStateHandle.toRoute<DetailRoute>().themeArea

    /** The season a show opens on, when its page was opened for one (a season in a collection). */
    private val routeSeasonId: UUID? = savedStateHandle.toRoute<DetailRoute>().seasonId?.let(UUID::fromString)

    /** Settings › Appearance: a collection's movies and shows in one row. */
    val collectionMoviesWithShows: Boolean get() = settings.settings.value.appearance.collectionMoviesWithShows
    private var themeSongLoaded = false

    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val pin = PinGate(viewModelScope, parental) { _events.send(it) }

    val musicActions = MusicActions(
        scope = viewModelScope,
        controller = musicPlayback,
        music = music,
        toast = { _events.send(it) },
        onPlaylistChanged = { id -> if (id == itemId && _state.value.item?.kind == ItemKind.Playlist) reloadPlaylist() },
    )

    init {
        load()
        viewModelScope.launch { shuffle.on.collect { on -> _state.update { it.copy(shuffle = on) } } }
        viewModelScope.launch { playback.stopped.collect(::refresh) }
        // Something else marked watched or not (a movie of this collection, an episode of this show):
        // the page follows. This page's own item it already shows as changed.
        viewModelScope.launch { repository.playedChanged.collect { id -> if (id != itemId) refresh(id) } }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            try {
                val loaded = repository.details(itemId)
                if (ageFilter.isLocked(itemId, listOfNotNull(loaded.seriesId))) {
                    askPin(loaded)
                    return@launch
                }
                val similarAsync = async { runSuspendCatching { repository.similar(itemId) }.getOrDefault(emptyList()) }
                val (details, selection) = withVersion(loaded)
                _state.update { it.copy(details = details, selection = selection) }
                when (details.item.kind) {
                    ItemKind.Series -> loadSeries(details)
                    ItemKind.Episode -> loadSeasonOf(details)
                    ItemKind.Collection -> loadCollection(details)
                    ItemKind.Album -> _state.update { it.copy(musicTracks = music.albumTracks(itemId)) }
                    ItemKind.Playlist -> {
                        val videos = runSuspendCatching { repository.playlistVideos(itemId) }.getOrDefault(emptyList())
                        _state.update { it.copy(musicTracks = music.playlistTracks(itemId), playlistVideos = videos) }
                    }
                    ItemKind.Artist -> {
                        val videos = runSuspendCatching { repository.artistVideos(itemId) }.getOrDefault(emptyList())
                        _state.update { it.copy(artistAlbums = music.artistAlbums(itemId), artistVideos = videos) }
                    }
                    else -> Unit
                }
                if (details.item.kind == ItemKind.Movie || details.item.kind == ItemKind.Series) loadThemeSong()
                val withSimilar = details.item.kind in setOf(ItemKind.Movie, ItemKind.Series, ItemKind.Album, ItemKind.MusicVideo)
                _state.update { it.copy(loading = false, similar = if (withSimilar) similarAsync.await() else emptyList()) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading details failed", e)
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    /**
     * The details as the file that plays shows them (tracks, picture format), and what plays of it.
     * A title with one file stays as it is.
     */
    private suspend fun withVersion(details: ItemDetails): Pair<ItemDetails, TrackSelection?> {
        val versionId = trackSelections.get(details.item.id)?.versionId ?: versions.choose(details)?.id
        val shown = details.forVersion(versionId)
        return shown to selectionOf(shown, versionId)
    }

    /**
     * What plays: a choice made for this title, else the last title's tracks
     * where they apply (as the player takes them over), else the server's defaults.
     */
    private suspend fun selectionOf(details: ItemDetails, versionId: String?): TrackSelection? {
        trackSelections.get(details.item.id)?.let { return it }
        val tracks = details.tracks ?: return versionId?.let { TrackSelection(null, null, it) }
        val preferences = serverPreferences.preferences.value ?: serverPreferences.refresh()
        val remembered = preferences?.let { rememberedTracks(settings.current().showTracks[details.showKey()], it, tracks) }
        val audio = remembered?.audio ?: tracks.defaultAudio
        val subtitle = when (val index = remembered?.subtitle) {
            // Under the last title's audio, not the server's default: a forced pick stays forced under its own language.
            null -> tracks.subtitleFor(audio)
            RememberedTracks.SUBTITLES_OFF -> null
            else -> index
        }
        return TrackSelection(audio, subtitle, versionId)
    }

    private var refreshJob: Job? = null

    /**
     * After playback of [stoppedId] stopped: watched state, resume point, episodes and next episode follow,
     * quietly, without the loading state. A show's pages move to the episode that was playing
     * (or the next one, once it was watched to the end), even when autoplay went on past the opened one.
     */
    private fun refresh(stoppedId: UUID) {
        if (_state.value.details == null) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                var loaded = repository.details(itemId)
                val target = when (loaded.item.kind) {
                    ItemKind.Series -> episodeAfter(stoppedId, loaded.item.id)
                    ItemKind.Episode -> episodeAfter(stoppedId, loaded.seriesId)
                    else -> null
                }
                if (loaded.item.kind == ItemKind.Episode && target != null && target.item.id != itemId) {
                    itemId = target.item.id
                    loaded = target
                }
                val (details, selection) = withVersion(loaded)
                _state.update { it.copy(details = details, selection = selection) }
                when (details.item.kind) {
                    ItemKind.Series -> {
                        val seasons = repository.seasons(details.item.id)
                        val next = runSuspendCatching { repository.nextEpisode(details.item.id) }.getOrNull()
                        _state.update { it.copy(seasons = seasons, nextEpisode = next) }
                        val season = seasons.firstOrNull { it.id == target?.seasonId }
                            ?: seasons.firstOrNull { it.id == _state.value.season?.id }
                        season?.let { selectSeason(it, details.item.id, focusId = target?.item?.id) }
                    }
                    ItemKind.Episode -> loadSeasonOf(details)
                    ItemKind.Collection -> loadCollection(details)
                    else -> Unit
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Refreshing details failed", e)
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
        // Music the user chose wins over the theme song.
        if (musicPlayback.isPlaying) return
        themeSongLoaded = true
        viewModelScope.launch {
            runSuspendCatching { music.themeSong(itemId)?.let(playback::audioSource) }
                .onSuccess { source -> _state.update { it.copy(themeSong = source) } }
                .onFailure { Log.w(TAG, "Loading the theme song failed", it) }
        }
    }

    /** Design: open a show on the first season with something unwatched. Specials (season 0) only when there is nothing else. */
    private suspend fun loadSeries(details: ItemDetails) {
        val seasons = repository.seasons(details.item.id)
        val regular = seasons.filter { it.number != 0 }.ifEmpty { seasons }
        val season = seasons.firstOrNull { it.id == routeSeasonId }
            ?: regular.firstOrNull { (it.unwatchedCount ?: 0) > 0 } ?: regular.lastOrNull()
        val next = runSuspendCatching { repository.nextEpisode(details.item.id) }.getOrNull()
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

    /** Design: a collection plays the title in progress, else the first one not yet watched. People or seasons in it do not play. */
    fun collectionNext(): MediaItem? {
        val items = _state.value.collectionItems.filter { it.kind in PLAYABLE_IN_COLLECTION }
        return items.firstOrNull { it.progress != null } ?: items.firstOrNull { !it.played } ?: items.firstOrNull()
    }

    /** A collection with nothing to watch plays its music: songs, albums and playlists in their order. */
    fun collectionHasMusic(): Boolean = _state.value.collectionItems.any { it.kind in MusicInCollection }

    private fun playCollectionMusic(from: MediaItem? = null) {
        val collection = _state.value.item ?: return
        val items = _state.value.collectionItems.filter { it.kind in MusicInCollection }
        if (items.isEmpty()) return
        viewModelScope.launch {
            runSuspendCatching {
                val songs = music.tracks(items.filter { it.kind == ItemKind.Song }.map { it.id }).associateBy { it.id }
                items.flatMap { item ->
                    when (item.kind) {
                        ItemKind.Song -> listOfNotNull(songs[item.id])
                        ItemKind.Album -> music.albumTracks(item.id)
                        else -> music.playlistTracks(item.id)
                    }
                }
            }
                .onSuccess { tracks ->
                    if (tracks.isEmpty()) return@onSuccess
                    musicPlayback.playTracks(null, collection.title, tracks, startTrackId = from?.id?.toString())
                    navigate(MusicRoute())
                }
                .onFailure { Log.w(TAG, "Loading the collection's music failed", it) }
        }
    }

    /** A song of a collection plays with the collection's music around it. */
    fun playCollectionSong(song: MediaItem) = playCollectionMusic(from = song)

    /** A season of a collection opens its show on that season. */
    fun openSeason(season: MediaItem) {
        val seriesId = season.seriesId ?: return openItem(season)
        navigate(DetailRoute(seriesId.toString(), themeArea = seriesId.toString(), seasonId = season.id.toString()))
    }

    fun openPerson(person: MediaItem) = navigate(PersonRoute(person.id.toString(), fromTitle = _state.value.item?.title))

    fun selectSeason(season: Season) {
        val seriesId = _state.value.item?.id ?: return
        viewModelScope.launch { selectSeason(season, seriesId) }
    }

    /** The episode row lands on [focusId] if given, else on the first unwatched episode. */
    private suspend fun selectSeason(season: Season, seriesId: UUID, focusId: UUID? = null) {
        _state.update { it.copy(season = season) }
        val episodes = runSuspendCatching { repository.episodes(seriesId, season.id) }
            .onFailure { Log.w(TAG, "Loading episodes failed", it) }
            .getOrDefault(emptyList())
        val focus = episodes.indexOfFirst { it.id == focusId }.takeIf { it >= 0 }
            ?: episodes.indexOfFirst { !it.played }.takeIf { it >= 0 } ?: 0
        _state.update { if (it.season?.id == season.id) it.copy(episodes = episodes, focusEpisode = focus) else it }
    }

    /** The episode of show [seriesId] to show after [stoppedId] played: it, or the next one once watched to the end. */
    private suspend fun episodeAfter(stoppedId: UUID, seriesId: UUID?): ItemDetails? {
        if (seriesId == null) return null
        val stopped = repository.details(stoppedId)
        if (stopped.item.kind != ItemKind.Episode || stopped.seriesId != seriesId) return null
        if (!stopped.item.played) return stopped
        val next = runSuspendCatching { repository.nextEpisode(seriesId) }.getOrNull() ?: return stopped
        return runSuspendCatching { repository.details(next.id) }.getOrDefault(stopped)
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
            ItemKind.Collection -> collectionNext()?.also { if (it.kind == ItemKind.Series) return playShow(it) } ?: return playCollectionMusic()
            else -> item
        }
        // The tracks the chips show are the ones that play: the player takes them instead of working them out again.
        if (target.id == item.id && trackSelections.get(item.id) == null) {
            current.selection?.let { trackSelections.set(item.id, it) }
        }
        navigate(PlayerRoute(target.id.toString(), fromStart))
    }

    /** A show of a collection plays where it stands; with nothing left to watch its page opens. */
    private fun playShow(show: MediaItem) {
        viewModelScope.launch {
            val episode = runSuspendCatching { repository.nextEpisode(show.id) }
                .onFailure { Log.w(TAG, "Loading the next episode failed", it) }
                .getOrNull()
            if (episode != null) navigate(PlayerRoute(episode.id.toString())) else openItem(show)
        }
    }

    /** A video of the page (an artist's music video, a playlist's video), the rest of them following it. */
    fun playVideo(video: MediaItem) {
        val item = _state.value.item ?: return
        pin.openTitle(ageFilter, video.id, video.title, video.officialRating) {
            navigate(PlayerRoute(video.id.toString(), queueOf = item.id.toString()))
        }
    }

    /** Album, artist or playlist in the music player, from [track] or from the start. */
    fun playMusic(track: MusicTrack? = null) {
        val item = _state.value.item ?: return
        navigate(MusicRoute(item.id.toString(), track?.id?.toString()))
    }

    /** Options for one song of the page (hold OK, or the menu key). */
    fun trackOptions(track: MusicTrack) {
        val item = _state.value.item ?: return
        val entry = track.playlistItemId.takeIf { item.kind == ItemKind.Playlist }
        musicActions.open(
            MusicTarget(
                title = track.title,
                subtitle = listOfNotNull(track.artist, track.album).joinToString(" · ").ifEmpty { null },
                tracks = { listOf(track) },
                mixFrom = track.id,
                playlistId = item.id.takeIf { entry != null },
                playlistEntryIds = listOfNotNull(entry),
            ),
        )
    }

    /** Options for every song of the album, artist or playlist. */
    fun sourceOptions() {
        val item = _state.value.item ?: return
        musicActions.open(
            MusicTarget(
                title = item.title,
                subtitle = item.parentTitle,
                mixFrom = item.id,
                tracks = {
                    val state = _state.value
                    when (item.kind) {
                        ItemKind.Artist -> music.artistTracks(item.id, state.artistAlbums.ifEmpty { null })
                        ItemKind.Playlist -> state.musicTracks.ifEmpty { music.playlistTracks(item.id) }
                        else -> state.musicTracks.ifEmpty { music.albumTracks(item.id) }
                    }
                },
            ),
        )
    }

    /** After songs went in or out: the songs and the item's counts. */
    private fun reloadPlaylist() {
        viewModelScope.launch {
            runSuspendCatching { Triple(repository.details(itemId), music.playlistTracks(itemId), repository.playlistVideos(itemId)) }
                .onSuccess { (details, tracks, videos) -> _state.update { it.copy(details = details, musicTracks = tracks, playlistVideos = videos) } }
        }
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
            runSuspendCatching { repository.setFavorite(item.id, favorite) }
                .onSuccess { toast(if (favorite) R.string.favorite_added else R.string.favorite_removed) }
                .onFailure { updateItem { current -> current.copy(isFavorite = !favorite) } }
        }
    }

    fun togglePlayed() {
        val item = _state.value.item ?: return
        if (item.kind == ItemKind.Collection) return toggleCollectionPlayed(item)
        val played = !item.played
        // The server drops the resume point both ways; the resume button and the episode row follow at once.
        val marked: (MediaItem) -> MediaItem = { it.copy(played = played, progress = null, remainingMinutes = null, resumePositionMs = 0) }
        updateItem(marked)
        _state.update { state -> state.copy(episodes = state.episodes.map { if (it.id == item.id) marked(it) else it }) }
        viewModelScope.launch {
            runSuspendCatching { repository.setPlayed(item.id, played) }
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
                        val next = runSuspendCatching { repository.nextEpisode(item.id) }.getOrNull()
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

    /**
     * A collection is seen when everything in it to watch is. Music, people and the like in it stay as they are,
     * so a mixed collection marks its titles one by one; the server would mark its songs too.
     */
    private fun toggleCollectionPlayed(collection: MediaItem) {
        val items = _state.value.collectionItems
        val watchable = items.filter { it.kind in WatchableInCollection }
        if (watchable.isEmpty()) return
        val played = !watchable.all { it.played }
        val mixed = watchable.size < items.size
        _state.update { state ->
            state.copy(collectionItems = state.collectionItems.map { if (it.kind in WatchableInCollection) it.copy(played = played) else it })
        }
        viewModelScope.launch {
            runSuspendCatching {
                if (mixed) watchable.forEach { repository.setPlayed(it.id, played) } else repository.setPlayed(collection.id, played)
            }
                .onSuccess { toast(if (played) R.string.marked_watched else R.string.marked_unwatched) }
                .onFailure { Log.w(TAG, "Marking the collection failed", it) }
            val details = _state.value.details ?: return@launch
            runSuspendCatching { loadCollection(details) }.onFailure { Log.w(TAG, "Reloading the collection failed", it) }
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

    /**
     * Another file of the title. Its tracks are other streams: the ones picked
     * carry over by language, else the file's defaults apply. Subtitles off stay off.
     * The pick holds for the movie, or for every episode of the show.
     */
    fun pickVersion(id: String, label: String) {
        val current = _state.value
        val details = current.details ?: return
        val version = details.versions.firstOrNull { it.id == id } ?: return
        val shown = details.forVersion(id)
        val old = current.selection
        val audio = old?.audio
            ?.let { index -> details.tracks?.audio?.firstOrNull { it.index == index } }
            ?.let { closestTrack(it.toKey(), shown.tracks?.audio.orEmpty()) }?.index
            ?: shown.tracks?.defaultAudio
        val subtitle = when {
            old != null && old.subtitle == null -> null
            else -> old?.subtitle
                ?.let { index -> details.tracks?.subtitles?.firstOrNull { it.index == index } }
                ?.let { closestTrack(it.toKey(), shown.tracks?.subtitles.orEmpty()) }?.index
                ?: shown.tracks?.subtitleFor(audio)
        }
        val selection = TrackSelection(audio, subtitle, id)
        trackSelections.set(itemId, selection)
        _state.update { it.copy(details = shown, selection = selection, trackPanel = null) }
        viewModelScope.launch {
            versions.remember(details, version)
            _events.send(UiEvent.Toast(R.string.version_set, listOf(label)))
        }
    }

    fun openEpisode(episode: MediaItem) = navigate(DetailRoute(episode.id.toString(), themeArea = episode.seriesId?.toString()))

    fun openItem(item: MediaItem) = navigate(DetailRoute(item.id.toString()))

    /** An album's artist page. */
    fun openArtist() {
        val artistId = _state.value.item?.artistId ?: return
        navigate(DetailRoute(artistId.toString()))
    }

    /** The full music player, from the mini player. */
    fun openNowPlaying() = navigate(MusicRoute())

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
        val PLAYABLE_IN_COLLECTION = setOf(ItemKind.Movie, ItemKind.Series, ItemKind.Episode, ItemKind.MusicVideo)
    }
}
