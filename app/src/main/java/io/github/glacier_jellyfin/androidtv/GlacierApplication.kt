package io.github.glacier_jellyfin.androidtv

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class GlacierApplication : Application() {

    init {
        // The Jellyfin SDK logs through kotlin-logging, which otherwise expects
        // an SLF4J binding on the classpath and crashes without one. Route it
        // to Logcat instead. Must run before the SDK is first touched.
        System.setProperty("kotlin-logging-to-android-native", "true")
    }
}
