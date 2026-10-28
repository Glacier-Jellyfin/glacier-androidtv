package io.github.glacier_jellyfin.androidtv.core.updater

/** One subsection of the release notes, e.g. "New" with its lines. */
data class NotesSection(val heading: String, val items: List<NotesItem>)

/** A line of the notes; [ref] is a trailing issue reference like "#142", shown apart. */
data class NotesItem(val text: String, val ref: String?)

/**
 * Reads the Markdown the release workflow publishes (a CHANGELOG.md section,
 * see its header): `###` or `##` headings with `-` or `*` lists below. An
 * indented line right after an item continues it, as wrapped items do. Bold,
 * italics and code marks are dropped; everything else is ignored.
 */
object ReleaseNotes {

    private val HEADING = Regex("""^#{2,3}\s+(.+)$""")
    private val ITEM = Regex("""^[-*]\s+(.+)$""")
    private val REF = Regex("""^(.*?)\s*\((#\d+)\)\s*$""")
    private val MARKS = Regex("""\*\*|__|`""")

    private val TRANSLATION = Regex("""<details lang="([a-z]{2})">.*?</details>""", RegexOption.DOT_MATCHES_ALL)
    private val HTML_HEADING = Regex("""^<h3>(.*)</h3>$""")
    private val HTML_ITEM = Regex("""^<li>(.*)</li>$""")

    /**
     * The notes of a release in [language]: release notes carry a translation
     * as `<details lang="de">` with `<h3>` and `<li>` lines (scripts/release-notes.sh),
     * which turns back into Markdown here. Without one, the English notes, never both.
     */
    fun localized(body: String, language: String): String {
        val translation = TRANSLATION.findAll(body).firstOrNull { it.groupValues[1] == language }
            ?: return TRANSLATION.replace(body, "")
        return translation.value.lineSequence().mapNotNull { line ->
            HTML_HEADING.matchEntire(line.trim())?.let { "### ${unescape(it.groupValues[1])}" }
                ?: HTML_ITEM.matchEntire(line.trim())?.let { "- ${unescape(it.groupValues[1])}" }
        }.joinToString("\n")
    }

    private fun unescape(text: String): String = text.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /** [fallbackHeading] titles lines that come before any heading. */
    fun parse(markdown: String, fallbackHeading: String): List<NotesSection> {
        val sections = mutableListOf<Pair<String, MutableList<String>>>()
        var inItem = false
        markdown.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (inItem && line.isNotEmpty() && raw.first().isWhitespace()) {
                val items = sections.last().second
                items[items.lastIndex] += " $line"
                return@forEach
            }
            inItem = false
            HEADING.matchEntire(line)?.let {
                sections += it.groupValues[1].trim() to mutableListOf()
                return@forEach
            }
            val item = ITEM.matchEntire(line)?.groupValues?.get(1) ?: return@forEach
            if (sections.isEmpty()) sections += fallbackHeading to mutableListOf()
            sections.last().second += item
            inItem = true
        }
        return sections.filter { it.second.isNotEmpty() }.map { (heading, items) ->
            NotesSection(heading, items.map(::toItem))
        }
    }

    private fun toItem(item: String): NotesItem {
        val ref = REF.matchEntire(item)
        val text = (ref?.groupValues?.get(1) ?: item).replace(MARKS, "").trim()
        return NotesItem(text, ref?.groupValues?.get(2))
    }
}
