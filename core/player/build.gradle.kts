plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.glacier_jellyfin.androidtv.core.player"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:log"))
    api(libs.media3.exoplayer)
    api(libs.media3.ui.compose)
    // SubtitleView: Media3 has no Compose subtitle renderer yet.
    api(libs.media3.ui)
    implementation(libs.media3.exoplayer.hls)
    // FFmpeg audio decoder for formats the device cannot decode (see core/ffmpeg/README.md).
    implementation(project(":core:ffmpeg"))
    // Full ASS/SSA rendering (signs, fonts, animation) that Media3's own parser lacks.
    api(libs.libass.media)
    constraints {
        // Media3 brings an older Guava with a known vulnerability. Remove once Media3's own is at least this new.
        api(libs.guava)
    }
}
