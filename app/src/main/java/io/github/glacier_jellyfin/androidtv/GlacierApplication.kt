package io.github.glacier_jellyfin.androidtv

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.svg.SvgDecoder
import dagger.hilt.android.HiltAndroidApp
import io.github.glacier_jellyfin.androidtv.channels.HomeChannelsSync
import io.github.glacier_jellyfin.androidtv.core.log.DebugOptions
import io.github.glacier_jellyfin.androidtv.core.log.DebugThrottle
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.core.player.Media3Logs
import io.github.glacier_jellyfin.androidtv.diagnostics.PlaybackCheckActivity
import javax.inject.Inject

@HiltAndroidApp
class GlacierApplication : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var homeChannels: HomeChannelsSync

    /** Coil's defaults plus SVG, for the language flags. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()

    override fun onCreate() {
        // First, so crashes while the app starts are recorded too.
        Log.install(this)
        Media3Logs.install()
        super.onCreate()
        Log.i("Glacier", "Started ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}) on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        // The playback check's own process only plays its samples.
        if (!PlaybackCheckActivity.isCheckProcess(getProcessName())) homeChannels.start()
        if (BuildConfig.DEBUG) registerThrottle()
    }

    /**
     * Debug builds take a speed cap for streams from adb, to try the app on a slow link:
     * `adb shell am broadcast -a io.github.glacier_jellyfin.androidtv.THROTTLE --el kbps 20000 -p <package>`;
     * 0 lifts it. `--ez fit false` lets the display scale a transcode down instead of the server; `--ez fixed true`
     * keeps a ladder on its first tier; `--ez pause true` pauses at the first picture; `--ez seglog true` logs every segment load;
     * `--ez probe true` runs the segment probe once;
     * `--ez probeold true` makes it ask for segments played long ago.
     */
    private fun registerThrottle() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.hasExtra("kbps")) DebugThrottle.bitsPerSecond = intent.getLongExtra("kbps", 0) * 1000
                if (intent.hasExtra("fixed")) {
                    DebugOptions.fixedTier = intent.getBooleanExtra("fixed", false)
                    Log.w("Debug", "Ladder stays on its first tier: ${DebugOptions.fixedTier}")
                }
                if (intent.hasExtra("probe")) DebugOptions.probe = intent.getBooleanExtra("probe", false)
                if (intent.hasExtra("probeold")) DebugOptions.probeOld = intent.getBooleanExtra("probeold", false)
                if (intent.hasExtra("seglog")) DebugOptions.segmentLog = intent.getBooleanExtra("seglog", false)
                if (intent.hasExtra("pause")) DebugOptions.pauseOnStart = intent.getBooleanExtra("pause", false)
                if (intent.hasExtra("fit")) {
                    DebugOptions.fitDisplay = intent.getBooleanExtra("fit", true)
                    Log.w("Debug", "Transcodes fit the display: ${DebugOptions.fitDisplay}")
                }
            }
        }
        registerReceiver(receiver, IntentFilter(THROTTLE_ACTION), RECEIVER_EXPORTED)
    }

    private companion object {
        const val THROTTLE_ACTION = "io.github.glacier_jellyfin.androidtv.THROTTLE"
    }

    init {
        // The Jellyfin SDK logs through kotlin-logging, which otherwise expects
        // an SLF4J binding on the classpath and crashes without one. Route it
        // to Logcat instead. Must run before the SDK is first touched.
        System.setProperty("kotlin-logging-to-android-native", "true")
    }
}
