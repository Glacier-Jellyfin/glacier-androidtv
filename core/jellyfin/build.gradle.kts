plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.glacier_jellyfin.androidtv.core.jellyfin"
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
    api(libs.jellyfin.core)
}
