import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Fuzz targets for the pure Kotlin logic that reads what servers and files hand the app.
// Plain JVM, no Android: the sources under test are compiled in from their modules (below),
// so the targets also build in the ClusterFuzzLite image, which has no Android SDK.
//
//   ./gradlew :fuzz:test                run every target on its seed inputs only (fast, deterministic)
//   JAZZER_FUZZ=1 ./gradlew :fuzz:test  fuzz for real, see CONTRIBUTING.md

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// Only files without android.* and Media3 imports can come along.
val productionSources = tasks.register<Sync>("productionSources") {
    val modules = layout.projectDirectory.dir("../core")
    from(modules.dir("data/src/main/java/io/github/glacier_jellyfin/androidtv/core/data/media")) {
        include("Tracks.kt", "NameOrder.kt")
        into("data")
    }
    from(modules.dir("player/src/main/java/io/github/glacier_jellyfin/androidtv/core/player")) {
        include("DolbyVisionRpu.kt", "Iec61937.kt")
        into("player")
    }
    into(layout.buildDirectory.dir("production-sources"))
}

sourceSets.main {
    kotlin.srcDir(productionSources)
}

dependencies {
    // Tracks.kt reads the SDK's MediaStream.
    implementation(libs.jellyfin.core)
    // The ClusterFuzzLite image brings its own Jazzer; the targets only compile against the API.
    compileOnly(libs.jazzer.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jazzer.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Fuzzing is not up to date just because the seeds passed before.
    inputs.property("jazzerFuzz", providers.environmentVariable("JAZZER_FUZZ").orElse(""))
}

// Unit tests of every module run as testDebugUnitTest; the targets' seed runs join them.
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs the fuzz targets on their seed inputs."
    dependsOn(tasks.test)
}

// The jars the ClusterFuzzLite image runs the targets from (.clusterfuzzlite/build.sh).
tasks.register<Sync>("fuzzRuntimeLibs") {
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("fuzz-libs"))
}
