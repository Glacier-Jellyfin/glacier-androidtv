plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Never applied: only puts the chosen Kotlin Gradle plugin on the build
    // classpath so AGP's built-in Kotlin compiles with it.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
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
            classpath("org.apache.commons:commons-lang3:3.21.0")
            classpath("org.apache.httpcomponents:httpclient:4.5.14")
            classpath("com.google.guava:guava:33.7.2-jre")
        }
    }
}

subprojects {
    configurations.matching { it.name == "androidLintTool" }.configureEach {
        listOf(
            "org.bouncycastle:bcprov-jdk18on:1.86",
            "org.bouncycastle:bcpkix-jdk18on:1.86",
            "org.bouncycastle:bcutil-jdk18on:1.86",
            "org.apache.commons:commons-lang3:3.21.0",
            "org.apache.httpcomponents:httpclient:4.5.14",
            "com.google.guava:guava:33.7.2-jre",
        ).forEach { dependencyConstraints.add(project.dependencies.constraints.create(it)) }
    }

    // Media3 brings Guava into the app, Hilt and KSP into the annotation processors.
    // Raises all of them past a known vulnerability, keeping the Android flavour in
    // the APK. Remove once Media3, Hilt and KSP bring at least this version.
    configurations.matching { it.isCanBeResolved && it.name != "androidLintTool" }.configureEach {
        val processor = name.startsWith("ksp") || name.contains("AnnotationProcessor")
        val flavour = if (processor) "jre" else "android"
        dependencyConstraints.add(project.dependencies.constraints.create("com.google.guava:guava:33.7.2-$flavour"))
    }
}
