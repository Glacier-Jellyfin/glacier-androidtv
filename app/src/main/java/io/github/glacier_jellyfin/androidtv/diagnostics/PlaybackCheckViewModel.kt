package io.github.glacier_jellyfin.androidtv.diagnostics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.core.player.CheckResult
import io.github.glacier_jellyfin.androidtv.core.player.CheckSample
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayback
import io.github.glacier_jellyfin.androidtv.core.player.PlaybackCheck
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaybackCheckState(
    val running: Boolean = false,
    /** The sample playing now. */
    val current: CheckSample? = null,
    val results: List<CheckResult> = emptyList(),
    val total: Int = PlaybackCheck.SAMPLES.size,
    /** The player of [current], for the hidden surface its decoder renders to. */
    val playback: GlacierPlayback? = null,
) {
    val done: Boolean get() = !running && results.isNotEmpty()
}

/**
 * The playback check in [PlaybackCheckActivity]'s own process. With [resume]
 * it goes on after a sample whose decoder took the previous process down.
 */
class PlaybackCheckViewModel(application: Application, dolbyVisionBaseLayer: Boolean, resume: Boolean) : AndroidViewModel(application) {

    private val check = PlaybackCheck(application, dolbyVisionBaseLayer)
    private val journal = PlaybackCheckJournal(application)
    private val _state = MutableStateFlow(PlaybackCheckState())
    val state: StateFlow<PlaybackCheckState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        if (resume) resume()
    }

    fun start() {
        if (job?.isActive == true) return
        journal.begin()
        Log.i(TAG, "Started")
        run(from = 0, earlier = emptyList())
    }

    fun stop() {
        if (job?.isActive != true) return
        job?.cancel()
        journal.end()
        Log.i(TAG, "Stopped after ${_state.value.results.size} of ${_state.value.total}")
    }

    private fun resume() {
        val entries = journal.read()
        val open = entries.open ?: return
        val crashed = CheckResult(PlaybackCheck.SAMPLES[open], CheckResult.Outcome.Crashed, error = "the player's process died")
        journal.finished(open, crashed)
        Log.w(TAG, crashed.describe())
        run(from = open + 1, earlier = entries.results + crashed)
    }

    private fun run(from: Int, earlier: List<CheckResult>) {
        _state.value = PlaybackCheckState(running = true, results = earlier)
        job = viewModelScope.launch {
            try {
                for (index in from until PlaybackCheck.SAMPLES.size) {
                    val sample = PlaybackCheck.SAMPLES[index]
                    _state.update { it.copy(current = sample) }
                    journal.starting(index)
                    val result = check.play(sample) { player -> _state.update { it.copy(playback = player) } }
                    journal.finished(index, result)
                    _state.update { it.copy(results = it.results + result) }
                }
                journal.end()
                // One block, so a bug report shows the whole check together.
                Log.i(TAG, "Results:\n" + _state.value.results.joinToString("\n") { "  " + it.describe() })
            } finally {
                _state.update { it.copy(running = false, current = null, playback = null) }
            }
        }
    }

    private companion object {
        const val TAG = "PlaybackCheck"
    }
}
