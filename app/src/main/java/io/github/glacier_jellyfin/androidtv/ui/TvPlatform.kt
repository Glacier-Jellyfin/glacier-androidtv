package io.github.glacier_jellyfin.androidtv.ui

import android.content.Context
import androidx.annotation.StringRes
import io.github.glacier_jellyfin.androidtv.R

/** The TV platform Glacier runs on, as the user knows it by name. */
enum class TvPlatform(@StringRes val label: Int) {
    AndroidTv(R.string.platform_android_tv),
    GoogleTv(R.string.platform_google_tv),
    FireTv(R.string.platform_fire_tv),
    ;

    companion object {
        /** Fire TV declares its own feature; Google TV is Android TV with the "Amati" experience on top. */
        fun of(context: Context): TvPlatform {
            val packages = context.packageManager
            return when {
                packages.hasSystemFeature(FIRE_TV) -> FireTv
                packages.hasSystemFeature(GOOGLE_TV) -> GoogleTv
                else -> AndroidTv
            }
        }

        private const val FIRE_TV = "amazon.hardware.fire_tv"
        private const val GOOGLE_TV = "com.google.android.feature.AMATI_EXPERIENCE"
    }
}
