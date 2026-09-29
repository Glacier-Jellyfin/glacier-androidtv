package io.github.glacier_jellyfin.androidtv.core.updater

/** One subsection of the release notes, e.g. "New" with its lines. */
data class NotesSection(val heading: String, val items: List<NotesItem>)

/** A line of the notes; [ref] is a trailing issue reference like "#142", shown apart. */
data class NotesItem(val text: String, val ref: String?)

/**
 * Reads the Markdown the release workflow publishes (a CHANGELOG.md section,
 * see its header): `###` or `##` headings with `-` or `*` lists below. Bold,
 * italics and code marks are dropped; everything else is ignored.
 */
object ReleaseNotes {

    private val HEADING = Regex("""^#{2,3}\s+(.+)$""")
    private val ITEM = Regex("""^[-*]\s+(.+)$""")
    private val REF = Regex("""^(.*?)\s*\((#\d+)\)\s*$""")
    private val MARKS = Regex("""\*\*|__|`""")

    /** [fallbackHeading] titles lines that come before any heading. */
    fun parse(markdown: String, fallbackHeading: String): List<NotesSection> {
        val sections = mutableListOf<Pair<String, MutableList<NotesItem>>>()
        markdown.lineSequence().map { it.trim() }.forEach { line ->
            HEADING.matchEntire(line)?.let {
                sections += it.groupValues[1].trim() to mutableListOf()
                return@forEach
            }
            val item = ITEM.matchEntire(line)?.groupValues?.get(1) ?: return@forEach
            if (sections.isEmpty()) sections += fallbackHeading to mutableListOf()
            val ref = REF.matchEntire(item)
            val text = (ref?.groupValues?.get(1) ?: item).replace(MARKS, "").trim()
            sections.last().second += NotesItem(text, ref?.groupValues?.get(2))
        }
        return sections.filter { it.second.isNotEmpty() }.map { (heading, items) -> NotesSection(heading, items) }
    }
}
