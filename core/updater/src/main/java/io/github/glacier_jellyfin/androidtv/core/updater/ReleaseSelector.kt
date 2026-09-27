package io.github.glacier_jellyfin.androidtv.core.updater

enum class UpdateChannel { Stable, Beta }

/** The parts of a GitHub release the updater cares about. */
data class Release(
    val tag: String,
    val isDraft: Boolean,
    val isPrerelease: Boolean,
    val assets: List<ReleaseAsset>,
)

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    /** GitHub's asset digest, e.g. "sha256:…", when the API provides one. */
    val digest: String?,
)

data class UpdateCandidate(
    val version: AppVersion,
    val release: Release,
    val apk: ReleaseAsset,
)

object ReleaseSelector {

    fun apkName(version: AppVersion): String = "glacier-androidtv-$version.apk"

    /**
     * Picks the newest release the channel allows that is strictly newer than
     * [installed]. Never returns a downgrade: a beta user who switches back to
     * Stable waits for the next stable release.
     *
     * A release is ignored when its tag does not parse, when GitHub's
     * pre-release flag disagrees with the tag, or when it lacks the APK asset.
     */
    fun select(releases: List<Release>, installed: AppVersion, channel: UpdateChannel): UpdateCandidate? =
        releases.asSequence()
            .filterNot { it.isDraft }
            .mapNotNull { release ->
                val version = AppVersion.parse(release.tag) ?: return@mapNotNull null
                if (version.isBeta != release.isPrerelease) return@mapNotNull null
                if (version.isBeta && channel == UpdateChannel.Stable) return@mapNotNull null
                val apk = release.assets.firstOrNull { it.name == apkName(version) } ?: return@mapNotNull null
                UpdateCandidate(version, release, apk)
            }
            .filter { it.version > installed }
            .maxByOrNull { it.version }
}
