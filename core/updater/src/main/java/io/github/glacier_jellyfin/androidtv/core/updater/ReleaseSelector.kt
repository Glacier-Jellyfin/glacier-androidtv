package io.github.glacier_jellyfin.androidtv.core.updater

import kotlinx.serialization.Serializable

enum class UpdateChannel { Stable, Beta }

/** The parts of a GitHub release the updater cares about. */
@Serializable
data class Release(
    val tag: String,
    val isDraft: Boolean,
    val isPrerelease: Boolean,
    val assets: List<ReleaseAsset>,
    /** Markdown release notes: the version's CHANGELOG.md section. */
    val body: String = "",
    /** ISO 8601, e.g. "2026-09-24T18:02:11Z". */
    val publishedAt: String? = null,
)

@Serializable
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    /** GitHub's asset digest, e.g. "sha256:…", when the API provides one. */
    val digest: String?,
)

@Serializable
data class UpdateCandidate(
    val version: AppVersion,
    val release: Release,
    val apk: ReleaseAsset,
    /** The releases between the installed version and [release], newest first, without their assets. */
    val skipped: List<Release> = emptyList(),
) {
    /** Every release the update brings, newest first: [release] and the [skipped] ones. */
    val changes: List<Release> get() = listOf(release) + skipped
}

object ReleaseSelector {

    /** Same name in every release, so releases/latest/download links stay stable. */
    const val APK_NAME = "glacier-androidtv.apk"

    /** Name used up to 0.2.1; still accepted for those releases. */
    fun legacyApkName(version: AppVersion): String = "glacier-androidtv-$version.apk"

    /**
     * Picks the newest release the channel allows that is strictly newer than
     * [installed]. Never returns a downgrade: a beta user who switches back to
     * Stable waits for the next stable release.
     *
     * A release is ignored when its tag does not parse, when GitHub's
     * pre-release flag disagrees with the tag, or when it lacks the APK asset.
     */
    fun select(releases: List<Release>, installed: AppVersion, channel: UpdateChannel): UpdateCandidate? {
        val newer = releases.asSequence()
            .filterNot { it.isDraft }
            .mapNotNull { release ->
                val version = AppVersion.parse(release.tag) ?: return@mapNotNull null
                if (version.isBeta != release.isPrerelease) return@mapNotNull null
                if (version.isBeta && channel == UpdateChannel.Stable) return@mapNotNull null
                version to release
            }
            .filter { (version, _) -> version > installed }
            .sortedByDescending { (version, _) -> version }
            .toList()
        val index = newer.indexOfFirst { (version, release) -> apkOf(release, version) != null }
        if (index < 0) return null
        val (version, release) = newer[index]
        // A release without an APK still tells what changed, so its notes come along.
        val skipped = newer.drop(index + 1).map { (_, it) -> it.copy(assets = emptyList()) }
        return UpdateCandidate(version, release, apkOf(release, version)!!, skipped)
    }

    private fun apkOf(release: Release, version: AppVersion): ReleaseAsset? =
        release.assets.firstOrNull { it.name == APK_NAME }
            ?: release.assets.firstOrNull { it.name == legacyApkName(version) }
}
