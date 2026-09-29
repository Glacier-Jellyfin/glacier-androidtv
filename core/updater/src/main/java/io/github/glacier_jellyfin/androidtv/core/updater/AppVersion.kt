package io.github.glacier_jellyfin.androidtv.core.updater

import kotlinx.serialization.Serializable

/**
 * A Glacier release version: `MAJOR.MINOR.PATCH` with an optional `-beta.N`.
 *
 * This is the only pre-release form Glacier publishes, so the ordering is
 * simpler than full SemVer: a stable release outranks every beta of the same
 * core version, betas order by their number.
 *
 * [versionCode] must stay identical to `versionCodeOf` in app/build.gradle.kts.
 */
@Serializable
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val beta: Int? = null,
) : Comparable<AppVersion> {

    val isBeta: Boolean get() = beta != null

    /** major * 1_000_000 + minor * 10_000 + patch * 100 + (beta number | 99). */
    val versionCode: Int get() = major * 1_000_000 + minor * 10_000 + patch * 100 + (beta ?: STABLE_SUFFIX)

    override fun compareTo(other: AppVersion): Int = versionCode.compareTo(other.versionCode)

    override fun toString(): String = "$major.$minor.$patch" + (beta?.let { "-beta.$it" } ?: "")

    /** For people: "1.4.0" or "1.4.0 Beta 2". */
    val displayName: String get() = "$major.$minor.$patch" + (beta?.let { " Beta $it" } ?: "")

    companion object {
        private const val STABLE_SUFFIX = 99
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-beta\.(\d+))?$""")

        /** Parses "1.4.0", "1.4.0-beta.2" or a tag like "v1.4.0". Returns null for anything else. */
        fun parse(text: String): AppVersion? {
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, beta) = match.destructured
            val version = AppVersion(
                major = major.toInt(),
                minor = minor.toInt(),
                patch = patch.toInt(),
                beta = beta.takeIf { it.isNotEmpty() }?.toInt(),
            )
            val inRange = version.minor <= 99 && version.patch <= 99 && (version.beta ?: 1) in 1..98
            return version.takeIf { inRange }
        }
    }
}
