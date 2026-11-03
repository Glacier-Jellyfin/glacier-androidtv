package io.github.glacier_jellyfin.androidtv.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.glacier_jellyfin.androidtv.core.designsystem.Accent
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTheme
import io.github.glacier_jellyfin.androidtv.core.player.DolbyVisionProfile7
import io.github.glacier_jellyfin.androidtv.core.player.PlaybackSetup
import io.github.glacier_jellyfin.androidtv.UiLocale

/**
 * The playback check, in a process of its own (":playbackcheck" in the
 * manifest): a decoder that crashes on a sample takes only this process down,
 * and the app opens the check again to go on with the next sample.
 *
 * Everything it needs comes with the intent, so this process never touches
 * the app's stored settings or session.
 */
class PlaybackCheckActivity : ComponentActivity() {

    private val viewModel: PlaybackCheckViewModel by viewModels {
        viewModelFactory {
            initializer {
                val setup = PlaybackSetup(
                    dolbyVisionProfile7 = runCatching { DolbyVisionProfile7.valueOf(intent.getStringExtra(EXTRA_PROFILE7).orEmpty()) }
                        .getOrDefault(DolbyVisionProfile7.AsIs),
                    iec61937 = intent.getBooleanExtra(EXTRA_IEC61937, false),
                )
                PlaybackCheckViewModel(application, setup, intent.getBooleanExtra(EXTRA_RESUME, false))
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val accent = runCatching { Accent.valueOf(intent.getStringExtra(EXTRA_ACCENT).orEmpty()) }.getOrDefault(Accent.Crevasse)
        setContent {
            GlacierTheme(accent = accent) {
                PlaybackCheckScreen(viewModel, onClose = ::finish)
            }
        }
    }

    companion object {
        private const val EXTRA_ACCENT = "accent"
        private const val EXTRA_PROFILE7 = "dolbyVisionProfile7"
        private const val EXTRA_IEC61937 = "iec61937"
        private const val EXTRA_RESUME = "resume"
        private const val PROCESS_SUFFIX = ":playbackcheck"

        fun start(context: Context, accent: Accent, setup: PlaybackSetup, resume: Boolean = false) {
            context.startActivity(
                Intent(context, PlaybackCheckActivity::class.java)
                    .putExtra(EXTRA_ACCENT, accent.name)
                    .putExtra(EXTRA_PROFILE7, setup.dolbyVisionProfile7.name)
                    .putExtra(EXTRA_IEC61937, setup.iec61937)
                    .putExtra(EXTRA_RESUME, resume),
            )
        }

        /** Opens the check again when its process died in the middle of a sample; true if it did. */
        fun resumeIfInterrupted(context: Context, accent: Accent, setup: PlaybackSetup): Boolean {
            if (PlaybackCheckJournal(context).interruptedAt() == null || running(context)) return false
            start(context, accent, setup, resume = true)
            return true
        }

        /** True in the check's own process, where the app's background work stays off. */
        fun isCheckProcess(processName: String): Boolean = processName.endsWith(PROCESS_SUFFIX)

        private fun running(context: Context): Boolean =
            context.getSystemService(ActivityManager::class.java)?.runningAppProcesses.orEmpty().any { isCheckProcess(it.processName) }
    }
}
