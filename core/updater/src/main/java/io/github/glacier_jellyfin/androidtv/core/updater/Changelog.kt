package io.github.glacier_jellyfin.androidtv.core.updater

/** One version's section of CHANGELOG.md; [date] as written there, e.g. "2026-10-27"; [body] is Markdown for [ReleaseNotes]. */
data class ChangelogEntry(val version: AppVersion, val date: String?, val body: String)

/**
 * Reads CHANGELOG.md as the store build carries it (Keep a Changelog):
 * `## [0.6.2] - 2026-10-27` starts a version, everything up to the next such
 * heading is its notes. [Unreleased] and anything before the first version are skipped.
 */
object Changelog {

    private val VERSION = Regex("""^##\s+\[([^\]]+)]\s*(?:-\s*(\d{4}-\d{2}-\d{2}))?.*$""")

    fun parse(markdown: String): List<ChangelogEntry> {
        val entries = mutableListOf<ChangelogEntry>()
        var current: Pair<AppVersion, String?>? = null
        val body = StringBuilder()
        fun close() {
            current?.let { (version, date) -> entries += ChangelogEntry(version, date, body.toString().trim()) }
            body.clear()
        }
        markdown.lineSequence().forEach { line ->
            val heading = VERSION.matchEntire(line.trim())
            if (heading != null) {
                close()
                current = AppVersion.parse(heading.groupValues[1])?.let { it to heading.groupValues[2].ifEmpty { null } }
            } else if (current != null) {
                body.appendLine(line)
            }
        }
        close()
        return entries
    }

    /** The entries after [from] up to and including [to], newest first. */
    fun between(entries: List<ChangelogEntry>, from: AppVersion, to: AppVersion): List<ChangelogEntry> =
        entries.filter { it.version > from && it.version <= to }.sortedByDescending { it.version }
}
