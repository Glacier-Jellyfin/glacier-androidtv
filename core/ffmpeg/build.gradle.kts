plugins {
    alias(libs.plugins.android.library)
}

// Media3's FFmpeg audio decoder, vendored from androidx/media (see README.md).
android {
    // Kept from Media3: DefaultRenderersFactory finds FfmpegAudioRenderer by this class name.
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The JNI wrapper is built only when FFmpeg has been built (scripts/build-ffmpeg.sh, done in CI).
    // Without it the app still builds and plays; FfmpegLibrary.isAvailable() is then false.
    if (file("src/main/jni/ffmpeg/include").exists()) {
        externalNativeBuild {
            cmake {
                path = file("src/main/jni/CMakeLists.txt")
                version = "3.31.6"
            }
        }
    }
}

dependencies {
    api(libs.media3.decoder)
    implementation(libs.media3.exoplayer)
    implementation(libs.androidx.annotation)
    compileOnly(libs.checker.qual)
}
