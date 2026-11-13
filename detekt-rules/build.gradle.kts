plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Project-specific detekt rules, loaded into every module's detekt run through
// detektPlugins (see the root build.gradle.kts). Plain JVM, no Android.

dependencies {
    compileOnly(libs.detekt.api)
    testImplementation(libs.detekt.api)
    testImplementation(libs.detekt.test)
    testImplementation(libs.junit)
}
