package io.github.glacier_jellyfin.androidtv.player

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.view.DisplayCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemDetails
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaVersion
import io.github.glacier_jellyfin.androidtv.core.data.media.Resolution
import io.github.glacier_jellyfin.androidtv.core.data.media.pickVersion
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.showKey
import io.github.glacier_jellyfin.androidtv.core.log.Log
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which file of a title with several plays (#8): the one picked for the movie
 * or show, else of the main cut the largest the device plays without
 * converting that fits the screen. Detail page and player ask the same, so
 * both show what plays.
 */
@Singleton
class VersionChooser @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playback: PlaybackRepository,
    private val settings: SettingsRepository,
) {
    /** What the server said plays as it is, per title: the answer does not change while the app runs. */
    private val direct = ConcurrentHashMap<String, Set<String>>()

    /** The version to play of [details]; null for a title with one file. */
    suspend fun choose(details: ItemDetails): MediaVersion? {
        if (details.versions.size < 2) return null
        val picked = settings.current().versions[details.showKey()]
        return pickVersion(details.versions, picked, directVersions(details), screen())
    }

    /** Remembers [version]: the very file for a movie, the kind of file for every episode of a show. */
    suspend fun remember(details: ItemDetails, version: MediaVersion) {
        val key = if (details.seriesId == null) version.key.copy(id = version.id) else version.key
        settings.update { it.copy(versions = it.versions + (details.showKey() to key)) }
    }

    private suspend fun directVersions(details: ItemDetails): Set<String>? {
        val id = details.item.id
        direct[id.toString()]?.let { return it }
        return try {
            playback.directVersions(id).also { direct[id.toString()] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Asking which versions play directly failed", e)
            null
        }
    }

    /** The panel's real size: Sony and others report the 1080p UI mode as the display mode. */
    private fun screen(): Resolution {
        val mode = DisplayCompat.getMode(context, ContextCompat.getDisplayOrDefault(context))
        return Resolution.of(mode.physicalWidth, mode.physicalHeight)
    }

    private companion object {
        const val TAG = "Versions"
    }
}
