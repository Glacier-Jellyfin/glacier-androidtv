package io.github.glacier_jellyfin.androidtv.core.updater

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.glacier_jellyfin.androidtv.core.log.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The changes since [previous], newest first, for the "What's new" dialog. */
data class WhatsNewPrompt(val previous: AppVersion, val current: AppVersion, val changes: List<ChangelogEntry>)

/**
 * "What's new" for a build a store updates ([UpdaterConfig.selfUpdate] off):
 * after the store installed a newer version, the home screen shows the
 * changelog the build carries (assets/CHANGELOG.md) once. A fresh
 * installation shows nothing. Applies to the whole device, like the updater.
 */
@Singleton
class WhatsNew @Inject constructor(
    @ApplicationContext private val context: Context,
    config: UpdaterConfig,
) {
    private val prefs = context.getSharedPreferences("whats_new", Context.MODE_PRIVATE)
    private val running: AppVersion? = AppVersion.parse(config.versionName)

    /** Only store builds have it; the self-updater shows its notes before the update instead. */
    val available: Boolean = !config.selfUpdate

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _prompt = MutableStateFlow<WhatsNewPrompt?>(null)
    val prompt: StateFlow<WhatsNewPrompt?> = _prompt.asStateFlow()

    private var started = false

    /**
     * Once per app start: offers the changes since the version of the last
     * start. That version moves on only once the dialog was shown, so an app
     * closed before the home screen loaded shows it next time.
     */
    fun start() {
        if (started || !available) return
        started = true
        val current = running ?: return
        val last = prefs.getString(KEY_LAST, null)?.let(AppVersion::parse)
        val prompt = last?.takeIf { it < current && _enabled.value }?.let { previous ->
            changesBetween(previous, current).takeIf { it.isNotEmpty() }?.let { WhatsNewPrompt(previous, current, it) }
        }
        if (prompt == null) remember(current) else _prompt.value = prompt
    }

    /** The home screen showed [prompt]; it does not come back. */
    fun promptShown() {
        val prompt = _prompt.value ?: return
        remember(prompt.current)
        _prompt.value = null
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs.edit { putBoolean(KEY_ENABLED, on) }
        if (!on) running?.let(::remember)
    }

    private fun changesBetween(last: AppVersion, current: AppVersion): List<ChangelogEntry> =
        runCatching { context.assets.open(ASSET).bufferedReader().use { it.readText() } }
            .onFailure { Log.w(TAG, "No changelog in this build", it) }
            .getOrNull()
            ?.let { Changelog.between(Changelog.parse(it), last, current) }
            .orEmpty()

    private fun remember(version: AppVersion) {
        prefs.edit { putString(KEY_LAST, version.toString()) }
    }

    private companion object {
        const val TAG = "WhatsNew"
        const val ASSET = "CHANGELOG.md"
        const val KEY_LAST = "last_version"
        const val KEY_ENABLED = "enabled"
    }
}
