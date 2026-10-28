plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Release version, e.g. "1.4.0" or "1.4.0-beta.2". Must match
 * core/updater's AppVersion, which applies the same versionCode scheme on device.
 */
val glacierVersion: String = providers.gradleProperty("glacier.version").get()

/**
 * major * 1_000_000 + minor * 10_000 + patch * 100 + (beta number | 99).
 * A stable release always outranks its own betas, so beta users receive it
 * as a regular update.
 */
fun versionCodeOf(version: String): Int {
    val match = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-beta\.(\d+))?$""").matchEntire(version)
        ?: error("glacier.version must look like 1.4.0 or 1.4.0-beta.2, was '$version'")
    val (major, minor, patch, beta) = match.destructured
    require(minor.toInt() <= 99 && patch.toInt() <= 99) { "minor and patch must be <= 99" }
    val suffix = if (beta.isEmpty()) 99 else beta.toInt().also { require(it in 1..98) { "beta must be 1..98" } }
    return major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + suffix
}

/** CHANGELOG.md and its translations as assets of the store builds, for "What's new" after a store update (core/updater WhatsNew). */
abstract class ChangelogAsset : DefaultTask() {
    @get:InputFiles
    abstract val changelogs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        changelogs.forEach { it.copyTo(outputDir.file(it.name).get().asFile, overwrite = true) }
    }
}

val changelogAsset = tasks.register<ChangelogAsset>("changelogAsset") {
    changelogs.from(rootProject.layout.projectDirectory.files("CHANGELOG.md", "CHANGELOG.de.md"))
}

/** The store builds; each delivers its own updates. Debug can play one with -Pglacier.store=amazon|play. */
val stores = listOf("amazon", "play")

androidComponents {
    // Debug carries it too, for trying "What's new" with -Pglacier.store.
    (stores + "debug").forEach { type ->
        onVariants(selector().withBuildType(type)) { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(changelogAsset, ChangelogAsset::outputDir)
        }
    }
}

android {
    namespace = "io.github.glacier_jellyfin.androidtv"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.glacier_jellyfin.androidtv"
        minSdk = 28
        targetSdk = 37
        versionName = glacierVersion
        versionCode = versionCodeOf(glacierVersion)
        // Empty: the GitHub releases API (core/updater ReleaseFeed.GITHUB).
        buildConfigField("String", "UPDATE_FEED", "\"\"")
        // The store that delivers the updates ("amazon", "play"); empty for the GitHub build and its self-updater.
        buildConfigField("String", "STORE", "\"\"")
    }

    signingConfigs {
        // Populated by the release workflow from repository secrets; see docs/RELEASING.md.
        val keystore = System.getenv("GLACIER_KEYSTORE_PATH")
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("GLACIER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GLACIER_KEY_ALIAS")
                keyPassword = System.getenv("GLACIER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // Local test releases for the updater, see docs/RELEASING.md.
            providers.gradleProperty("glacier.updateFeed").orNull?.let { buildConfigField("String", "UPDATE_FEED", "\"$it\"") }
            // Tries out a store build's behaviour ("What's new", no updater) on a debug build.
            providers.gradleProperty("glacier.store").orNull?.let {
                require(it in stores) { "glacier.store must be one of $stores, was '$it'" }
                buildConfigField("String", "STORE", "\"$it\"")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // Optimised like release but signed with the local debug key and
        // installable next to it: for measuring performance on real devices.
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        // The releases for the Amazon Appstore (APK) and Google Play (app bundle): no self-updater
        // and no install permission (src/store/AndroidManifest.xml) but "What's new" from the
        // changelog, see docs/RELEASING.md.
        stores.forEach { store ->
            create(store) {
                initWith(getByName("release"))
                buildConfigField("String", "STORE", "\"$store\"")
                matchingFallbacks += "release"
            }
        }
    }

    sourceSets {
        stores.forEach { getByName(it).manifest.srcFile("src/store/AndroidManifest.xml") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Settings › Account switches the interface language in the app, so every language ships in one piece.
    bundle {
        language {
            enableSplit = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":core:player"))
    implementation(libs.media3.session)
    implementation(project(":core:updater"))
    implementation(project(":core:log"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    implementation(libs.hilt.viewmodel.compose)
    ksp(libs.hilt.compiler)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // Language flags (assets/flags) are SVGs.
    implementation(libs.coil.svg)
    // The QR code for downloading the log (Settings › System › Diagnostics).
    implementation(libs.qrcodegen)
    // Channels and "Watch next" on the Android TV home screen, refreshed in the background.
    implementation(libs.androidx.tvprovider)
    implementation(libs.androidx.work.runtime)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.tv.material)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
