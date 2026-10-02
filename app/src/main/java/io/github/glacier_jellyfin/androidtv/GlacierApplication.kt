package io.github.glacier_jellyfin.androidtv

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.svg.SvgDecoder
import dagger.hilt.android.HiltAndroidApp
import io.github.glacier_jellyfin.androidtv.channels.HomeChannelsSync
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.core.player.Media3Logs
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
        homeChannels.start()
    }

    init {
        // The Jellyfin SDK logs through kotlin-logging, which otherwise expects
        // an SLF4J binding on the classpath and crashes without one. Route it
        // to Logcat instead. Must run before the SDK is first touched.
        System.setProperty("kotlin-logging-to-android-native", "true")
    }
}
