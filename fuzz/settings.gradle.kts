// Standalone build for the ClusterFuzzLite image (.clusterfuzzlite/build.sh), which has no
// Android SDK: ./gradlew -p fuzz ... configures only this project. From the repository root
// the same project is part of the main build (see settings.gradle.kts there).
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "fuzz"
