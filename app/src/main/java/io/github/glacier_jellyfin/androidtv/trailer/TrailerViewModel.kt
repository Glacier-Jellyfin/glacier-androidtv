package io.github.glacier_jellyfin.androidtv.trailer

import android.content.Context
import io.github.glacier_jellyfin.androidtv.core.log.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemDetails
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LocalTrailer
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.Trailer
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrDetails
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrMediaType
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.Trailers
import io.github.glacier_jellyfin.androidtv.core.data.media.YouTubeTrailer
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayer
import io.github.glacier_jellyfin.androidtv.core.player.StreamRequest
import io.github.glacier_jellyfin.androidtv.music.MusicController
import io.github.glacier_jellyfin.androidtv.navigation.PlayerRoute
import io.github.glacier_jellyfin.androidtv.navigation.TrailerRoute
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** The big button next to the trailer controls and on the end screen. */
enum class TitleAction { PlayMovie, ResumeMovie, WatchShow, PlayEpisode }

data class TrailerUiState(
    val details: ItemDetails? = null,
    val trailers: List<Trailer> = emptyList(),
    val index: Int = 0,
    /** Bumped for every start, so "watch again" gets a fresh YouTube player. */
    val attempt: Int = 0,
    /** Until the trailer's first frame. */
    val loading: Boolean = true,
    val failed: Boolean = false,
    /** The title has no trailer that can be played here. */
    val empty: Boolean = false,
    val ended: Boolean = false,
    /** Playing or about to; drives the play/pause button. */
    val playWhenReady: Boolean = true,
    /** Local trailers play in Media3; YouTube ones in [YouTubePlayer]. */
    val player: ExoPlayer? = null,
    /** Seconds until the next trailer starts, on the end screen. */
    val countdown: Int? = null,
    /** Lengths learned while playing (YouTube reports them only then), by trailer index. */
    val durations: Map<Int, Long> = emptyMap(),
    /** A Seerr title: not in the library, so nothing to play or mark as favourite. */
    val seerr: Boolean = false,
) {
    val item: MediaItem? get() = details?.item
    val current: Trailer? get() = trailers.getOrNull(index)
    val next: Trailer? get() = trailers.getOrNull(index + 1)

    fun durationOf(index: Int): Long? = durations[index] ?: (trailers.getOrNull(index) as? LocalTrailer)?.durationMs

    val titleAction: TitleAction?
        get() = if (seerr) null else when (item?.kind) {
            ItemKind.Movie -> if ((item?.resumePositionMs ?: 0) > 0) TitleAction.ResumeMovie else TitleAction.PlayMovie
            ItemKind.Series -> TitleAction.WatchShow
            ItemKind.Episode -> TitleAction.PlayEpisode
            else -> null
        }
}

data class TrailerProgress(val positionMs: Long = 0, val durationMs: Long = 0)

@HiltViewModel
class TrailerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val repository: DetailRepository,
    private val playback: PlaybackRepository,
    private val settings: SettingsRepository,
    private val musicPlayback: MusicController,
    private val seerrRepository: SeerrRepository,
) : ViewModel(), YouTubeListener {

    private val route = savedStateHandle.toRoute<TrailerRoute>()
    private val itemId = route.itemId?.let(UUID::fromString)

    private val _state = MutableStateFlow(TrailerUiState())
    val state: StateFlow<TrailerUiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(TrailerProgress())
    val progress: StateFlow<TrailerProgress> = _progress.asStateFlow()

    private val _youTube = Channel<YouTubeCommand>(Channel.BUFFERED)
    val youTubeCommands = _youTube.receiveAsFlow()

    /** Fires when the screen should close. */
    private val _finished = Channel<Unit>(Channel.CONFLATED)
    val finished = _finished.receiveAsFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var opening: Job? = null
    private var ticker: Job? = null
    private var countdown: Job? = null
    private var loadTimeout: Job? = null
    /** Set once the title starts: a second OK must not navigate again. */
    private var leaving = false
    private var closed = false
    /**
     * Where a YouTube seek is headed. The player keeps reporting the old
     * position for a moment; quick presses add up from here instead.
     */
    private var seekTarget: Long? = null
    private var seekStartedAt = 0L

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> _state.update { it.copy(loading = false) }
                Player.STATE_ENDED -> onEnded()
                else -> Unit
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _state.update { it.copy(playWhenReady = playWhenReady) }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Trailer playback failed", error)
            fail()
        }
    }

    init {
        // A trailer ends the music; it does not come back on its own afterwards.
        musicPlayback.pause()
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                if (itemId == null) {
                    loadSeerr()
                    return@launch
                }
                val details = repository.details(itemId)
                val local = if (details.trailers.localCount > 0) {
                    runCatching { repository.localTrailers(itemId) }
                        .onFailure { Log.w(TAG, "Loading local trailers failed", it) }
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                // Files on the server first: they need no third party and play in the best quality.
                val trailers = local + details.trailers.youTube
                _state.update { it.copy(details = details, trailers = trailers, empty = trailers.isEmpty(), loading = trailers.isNotEmpty()) }
                if (trailers.isNotEmpty()) play(0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading the title failed", e)
                _state.update { it.copy(empty = true, loading = false) }
            }
        }
    }

    /** Trailers of a title that is not in the library: Seerr's YouTube links only. */
    private suspend fun loadSeerr() {
        val type = SeerrMediaType.valueOf(checkNotNull(route.seerrType) { "Trailer route without a title" })
        val seerr = seerrRepository.details(type, checkNotNull(route.tmdbId))
        val details = seerr.asItemDetails()
        _state.update {
            it.copy(details = details, trailers = seerr.trailers, seerr = true, empty = seerr.trailers.isEmpty(), loading = seerr.trailers.isNotEmpty())
        }
        if (seerr.trailers.isNotEmpty()) play(0)
    }

    /** Starts trailer [index] from the beginning. */
    fun play(index: Int) {
        val trailer = _state.value.trailers.getOrNull(index) ?: return
        opening?.cancel()
        countdown?.cancel()
        releasePlayer()
        seekTarget = null
        _progress.value = TrailerProgress(durationMs = _state.value.durationOf(index) ?: 0)
        _state.update {
            it.copy(index = index, attempt = it.attempt + 1, loading = true, failed = false, ended = false, playWhenReady = true, countdown = null)
        }
        startLoadTimeout()
        when (trailer) {
            is LocalTrailer -> openLocal(trailer)
            // The YouTube player starts itself once the screen shows it.
            is YouTubeTrailer -> Unit
        }
    }

    private fun openLocal(trailer: LocalTrailer) {
        opening = viewModelScope.launch {
            try {
                val source = playback.open(trailer.id, startMs = 0, audioIndex = null, subtitleIndex = null)
                val player = GlacierPlayer.create(
                    context,
                    StreamRequest(url = source.url, isHls = source.isHls, headers = source.headers, startPositionMs = 0),
                ).player
                player.addListener(listener)
                player.playWhenReady = true
                _state.update { it.copy(player = player) }
                startTicker(player)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Opening the trailer failed", e)
                fail()
            }
        }
    }

    private fun startTicker(player: ExoPlayer) {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                onProgress(player.currentPosition, player.duration.coerceAtLeast(0))
                delay(TICK_MS)
            }
        }
    }

    private fun onProgress(positionMs: Long, durationMs: Long) {
        _progress.value = TrailerProgress(positionMs, durationMs)
        val index = _state.value.index
        if (durationMs > 0 && _state.value.durations[index] != durationMs) {
            _state.update { it.copy(durations = it.durations + (index to durationMs)) }
        }
    }

    /** A trailer that shows nothing for this long counts as failed (a stuck WebView, an unreachable server). */
    private fun startLoadTimeout() {
        loadTimeout?.cancel()
        loadTimeout = viewModelScope.launch {
            delay(LOAD_TIMEOUT_MS)
            if (_state.value.loading) fail()
        }
    }

    private fun fail() {
        releasePlayer()
        _state.update { it.copy(failed = true, loading = false) }
    }

    private fun onEnded() {
        releasePlayer()
        _progress.update { it.copy(positionMs = it.durationMs) }
        _state.update { it.copy(ended = true, loading = false, playWhenReady = false) }
        // Without "play trailers one after another" the end screen waits for a choice.
        if (_state.value.next != null && settings.settings.value.playback.trailerAutoNext) startCountdown()
    }

    /** The end screen starts the next trailer after [COUNTDOWN_SECONDS] (design default). */
    private fun startCountdown() {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            for (seconds in COUNTDOWN_SECONDS downTo 1) {
                _state.update { it.copy(countdown = seconds) }
                delay(1_000)
            }
            playNext()
        }
    }

    fun playNext() = play(_state.value.index + 1)

    fun replay() = play(_state.value.index)

    fun togglePlay() {
        val state = _state.value
        if (state.ended || state.failed) return replay()
        if (state.loading) return
        val playing = !state.playWhenReady
        state.player?.let { if (playing) it.play() else it.pause() }
        if (state.current is YouTubeTrailer) {
            _youTube.trySend(if (playing) YouTubeCommand.Play else YouTubeCommand.Pause)
            _state.update { it.copy(playWhenReady = playing) }
        }
    }

    fun pause() {
        val state = _state.value
        if (!state.playWhenReady || state.ended) return
        state.player?.pause()
        if (state.current is YouTubeTrailer) {
            _youTube.trySend(YouTubeCommand.Pause)
            _state.update { it.copy(playWhenReady = false) }
        }
    }

    fun seekBy(deltaMs: Long) {
        val state = _state.value
        if (state.loading || state.ended || state.failed) return
        val duration = _progress.value.durationMs
        val pending = seekTarget?.takeIf { System.currentTimeMillis() - seekStartedAt < SEEK_SETTLE_MS }
        val target = ((pending ?: _progress.value.positionMs) + deltaMs).coerceAtLeast(0)
        // Past the end ends the trailer (design).
        if (duration > 0 && target >= duration) return onEnded()
        state.player?.seekTo(target)
        if (state.current is YouTubeTrailer) {
            seekTarget = target
            seekStartedAt = System.currentTimeMillis()
            _youTube.trySend(YouTubeCommand.SeekTo(target))
        }
        _progress.update { it.copy(positionMs = target) }
    }

    /** Plays the title itself; the trailer screen is dropped from the back stack. */
    fun playTitle() {
        val state = _state.value
        val item = state.item ?: return
        if (leaving) return
        leaving = true
        countdown?.cancel()
        viewModelScope.launch {
            val target = when (item.kind) {
                ItemKind.Series -> runCatching { repository.nextEpisode(item.id) }.getOrNull()?.id
                else -> item.id
            }
            if (target == null) {
                // A show without an episode to play: its page lists what there is.
                close()
            } else {
                pause()
                _events.send(UiEvent.Navigate(PlayerRoute(target.toString()), replace = true))
            }
        }
    }

    fun toggleFavorite() {
        if (_state.value.seerr) return
        val details = _state.value.details ?: return
        val favorite = !details.item.isFavorite
        setFavorite(favorite)
        viewModelScope.launch {
            runCatching { repository.setFavorite(details.item.id, favorite) }
                .onSuccess { _events.send(UiEvent.Toast(if (favorite) R.string.favorite_added else R.string.favorite_removed)) }
                .onFailure { setFavorite(!favorite) }
        }
    }

    private fun setFavorite(favorite: Boolean) = _state.update { state ->
        state.copy(details = state.details?.let { it.copy(item = it.item.copy(isFavorite = favorite)) })
    }

    /** Once: Back can arrive twice, as a key and as the system's back callback. */
    fun close() {
        if (closed) return
        closed = true
        countdown?.cancel()
        _finished.trySend(Unit)
    }

    // YouTube player reports; ignored when they belong to a trailer the screen has left.

    private fun isCurrent(videoId: String): Boolean {
        val state = _state.value
        return (state.current as? YouTubeTrailer)?.videoId == videoId && !state.ended && !state.failed
    }

    override fun onYouTubeState(videoId: String, state: Int) {
        if (!isCurrent(videoId)) return
        when (state) {
            YT_PLAYING -> _state.update { it.copy(loading = false, playWhenReady = true) }
            YT_PAUSED -> _state.update { it.copy(playWhenReady = false) }
            YT_ENDED -> onEnded()
        }
    }

    override fun onYouTubeProgress(videoId: String, positionMs: Long, durationMs: Long) {
        if (!isCurrent(videoId) || _state.value.loading) return
        seekTarget?.let { target ->
            val settled = kotlin.math.abs(positionMs - target) < SEEK_TOLERANCE_MS
            if (!settled && System.currentTimeMillis() - seekStartedAt < SEEK_SETTLE_MS) return
            seekTarget = null
        }
        onProgress(positionMs, durationMs)
    }

    override fun onYouTubeError(videoId: String, code: Int) {
        if (!isCurrent(videoId)) return
        Log.w(TAG, "YouTube player error $code for $videoId")
        fail()
    }

    private fun releasePlayer() {
        ticker?.cancel()
        loadTimeout?.cancel()
        _state.value.player?.let {
            it.removeListener(listener)
            it.release()
        }
        _state.update { it.copy(player = null) }
    }

    override fun onCleared() {
        releasePlayer()
    }

    private companion object {
        const val TAG = "Trailer"
        const val TICK_MS = 500L
        const val LOAD_TIMEOUT_MS = 20_000L
        const val COUNTDOWN_SECONDS = 8
        const val SEEK_SETTLE_MS = 2_000L
        const val SEEK_TOLERANCE_MS = 3_000L

        // YouTube IFrame API player states.
        const val YT_ENDED = 0
        const val YT_PLAYING = 1
        const val YT_PAUSED = 2
    }
}

/** The fields the trailer screen shows, for a title that only Seerr knows. */
private fun SeerrDetails.asItemDetails(): ItemDetails {
    val seerr = item
    return ItemDetails(
        item = MediaItem(
            // Never sent to Jellyfin: favourites and playback are off for Seerr titles.
            id = UUID(0L, seerr.tmdbId.toLong()),
            kind = if (seerr.type == SeerrMediaType.Movie) ItemKind.Movie else ItemKind.Series,
            title = seerr.title,
            sortName = null,
            year = seerr.year,
            communityRating = rating,
            officialRating = null,
            runtimeMinutes = runtimeMinutes.takeIf { seerr.type == SeerrMediaType.Movie },
            genres = genres,
            overview = seerr.overview,
            parentTitle = null,
            seasonNumber = null,
            episodeNumber = null,
            progress = null,
            remainingMinutes = null,
            unwatchedCount = null,
            isFavorite = false,
            played = false,
            childCount = null,
            quality = null,
            posterUrl = seerr.posterUrl,
            thumbUrl = seerr.backdropUrl,
            backdropUrl = seerr.backdropUrl,
        ),
        cast = emptyList(),
        tracks = null,
        trailers = Trailers(localCount = 0, youTube = trailers),
        seriesId = null,
        seasonId = null,
        premiereDate = null,
        seasonCount = seasons.size.takeIf { it > 0 },
        episodeCount = null,
    )
}
