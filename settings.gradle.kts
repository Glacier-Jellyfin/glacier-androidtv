pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "glacier-androidtv"

include(
    ":app",
    ":core:designsystem",
    ":core:jellyfin",
    ":core:data",
    ":core:player",
    ":core:ffmpeg",
    ":core:updater",
)
