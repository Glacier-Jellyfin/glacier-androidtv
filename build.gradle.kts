plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Never applied: only puts the chosen Kotlin Gradle plugin on the build
    // classpath so AGP's built-in Kotlin compiles with it.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover)
}

// Aggregated coverage report: ./gradlew koverHtmlReport koverXmlReport. No
// verification rule, coverage is reported but never fails the build.
dependencies {
    subprojects.forEach { kover(it) }
}

// Libraries AGP brings onto the build classpath and Lint's own classpath,
// raised to versions without known vulnerabilities until AGP ships them
// itself. They only run during the build and never end up in the APK. Remove
// an entry once AGP's own version is at least as new (./gradlew
// buildEnvironment, ./gradlew :app:dependencies --configuration androidLintTool).
buildscript {
    dependencies {
        constraints {
            classpath("org.bouncycastle:bcprov-jdk18on:1.86")
            classpath("org.bouncycastle:bcpkix-jdk18on:1.86")
            classpath("org.bouncycastle:bcutil-jdk18on:1.86")
            classpath("org.bitbucket.b_c:jose4j:0.9.7")
            classpath("org.jdom:jdom2:2.0.6.1")
            classpath("org.freemarker:freemarker:2.3.35")
            classpath("org.apache.commons:commons-lang3:3.21.0")
            classpath("org.apache.httpcomponents:httpclient:4.5.14")
            classpath("com.google.guava:guava:33.8.0-jre")
        }
    }
}

subprojects {
    // The detekt rules module is plain JVM: the Android dependency constraints below do not apply to it.
    val isJvmOnly = path == ":detekt-rules"
    apply(plugin = "dev.detekt")
    apply(plugin = "org.jetbrains.kotlinx.kover")

    // Standard rules plus ktlint formatting; only findings missing from the
    // module's detekt-baseline.xml fail the build (./gradlew detektBaseline
    // rewrites it, only for deliberate clean-ups).
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        baseline = file("detekt-baseline.xml")
        parallel = true
        // ./gradlew detekt -PdetektAutoCorrect rewrites files with ktlint's fixes.
        autoCorrect = providers.gradleProperty("detektAutoCorrect").isPresent
    }
    dependencies.add("detektPlugins", rootProject.libs.detekt.formatting)
    dependencies.add("detektPlugins", project(":detekt-rules"))
    // SARIF next to the HTML/XML lint reports, uploaded to code scanning by CI.
    plugins.withId("com.android.base") {
        extensions.configure<com.android.build.api.dsl.CommonExtension>("android") {
            lint.sarifReport = true
        }
    }
    tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
        reports {
            sarif.required.set(true)
            html.required.set(true)
        }
    }

    configurations.matching { it.name == "androidLintTool" }.configureEach {
        listOf(
            "org.bouncycastle:bcprov-jdk18on:1.86",
            "org.bouncycastle:bcpkix-jdk18on:1.86",
            "org.bouncycastle:bcutil-jdk18on:1.86",
            "org.apache.commons:commons-lang3:3.21.0",
            "org.apache.httpcomponents:httpclient:4.5.14",
            "com.google.guava:guava:33.8.0-jre",
        ).forEach { dependencyConstraints.add(project.dependencies.constraints.create(it)) }
    }

    // Media3 brings Guava into the app, Hilt and KSP into the annotation processors.
    // Raises all of them past a known vulnerability, keeping the Android flavour in
    // the APK. Remove once Media3, Hilt and KSP bring at least this version.
    configurations.matching { it.isCanBeResolved && it.name != "androidLintTool" && !isJvmOnly }.configureEach {
        val processor = name.startsWith("ksp") || name.contains("AnnotationProcessor")
        val flavour = if (processor) "jre" else "android"
        dependencyConstraints.add(project.dependencies.constraints.create("com.google.guava:guava:33.8.0-$flavour"))
    }

    // Robolectric brings Bouncy Castle into the unit tests. Raised past known
    // vulnerabilities. Remove once Robolectric brings at least this version.
    configurations.matching { it.isCanBeResolved && it.name != "androidLintTool" && !isJvmOnly }.configureEach {
        listOf(
            "org.bouncycastle:bcprov-jdk18on:1.86",
            "org.bouncycastle:bcpkix-jdk18on:1.86",
            "org.bouncycastle:bcutil-jdk18on:1.86",
        ).forEach { dependencyConstraints.add(project.dependencies.constraints.create(it)) }
    }
}
