package io.github.glacier_jellyfin.androidtv

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.svg.SvgDecoder
import dagger.hilt.android.HiltAndroidApp
import io.github.glacier_jellyfin.androidtv.core.log.Log

@HiltAndroidApp
class GlacierApplication : Application(), SingletonImageLoader.Factory {

    /** Coil's defaults plus SVG, for the language flags. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()

    override fun onCreate() {
        // First, so crashes while the app starts are recorded too.
        Log.install(this)
        super.onCreate()
        Log.i("Glacier", "Started ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}) on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
    }

    init {
        // The Jellyfin SDK logs through kotlin-logging, which otherwise expects
        // an SLF4J binding on the classpath and crashes without one. Route it
        // to Logcat instead. Must run before the SDK is first touched.
        System.setProperty("kotlin-logging-to-android-native", "true")
    }
}
