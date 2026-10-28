package io.github.glacier_jellyfin.androidtv.core.updater

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {

    private val bilingual = """
        ### Improved
        - Faster libraries (#12)

        <details lang="de">
        <summary>Deutsch</summary>

        <h3>Verbessert</h3>
        <ul>
        <li>Schnellere Bibliotheken &amp; &lt;Suche&gt; (#12)</li>
        </ul>
        </details>
    """.trimIndent()

    @Test
    fun `picks the translation for its language`() {
        assertEquals(
            listOf(NotesSection("Verbessert", listOf(NotesItem("Schnellere Bibliotheken & <Suche>", "#12")))),
            ReleaseNotes.parse(ReleaseNotes.localized(bilingual, "de"), fallbackHeading = "Changes"),
        )
    }

    @Test
    fun `other languages get the English notes without the translation`() {
        assertEquals(
            listOf(NotesSection("Improved", listOf(NotesItem("Faster libraries", "#12")))),
            ReleaseNotes.parse(ReleaseNotes.localized(bilingual, "fr"), fallbackHeading = "Changes"),
        )
    }

    @Test
    fun `older apps read only the English notes`() {
        assertEquals(
            listOf(NotesSection("Improved", listOf(NotesItem("Faster libraries", "#12")))),
            ReleaseNotes.parse(bilingual, fallbackHeading = "Changes"),
        )
    }

    @Test
    fun `reads changelog subsections with references`() {
        val notes = ReleaseNotes.parse(
            """
            ### New
            - Trickplay preview while seeking (#142)
            - New accent **Firn**

            ### Fixed
            * Rendering of `ASS` subtitles (#151)
            """.trimIndent(),
            fallbackHeading = "Changes",
        )
        assertEquals(
            listOf(
                NotesSection("New", listOf(NotesItem("Trickplay preview while seeking", "#142"), NotesItem("New accent Firn", null))),
                NotesSection("Fixed", listOf(NotesItem("Rendering of ASS subtitles", "#151"))),
            ),
            notes,
        )
    }

    @Test
    fun `lines before any heading get the fallback heading, empty sections are dropped`() {
        val notes = ReleaseNotes.parse("Intro text\n- First\n\n## Known issues\n\n## Fixed\n- Crash (#9)\n", fallbackHeading = "Changes")
        assertEquals(listOf("Changes", "Fixed"), notes.map { it.heading })
        assertEquals(NotesItem("First", null), notes[0].items.single())
    }

    @Test
    fun `a reference in the middle of a line stays in the text`() {
        val item = ReleaseNotes.parse("- Fixes (#12) and more", "Changes").single().items.single()
        assertEquals(NotesItem("Fixes (#12) and more", null), item)
    }

    @Test
    fun `indented lines continue a wrapped item, also with its reference`() {
        val notes = ReleaseNotes.parse(
            "### Fixed\n- The first update no longer stops\n  on the installing screen (#7)\n- Short\n\n  Not part of it\n",
            "Changes",
        )
        assertEquals(
            listOf(NotesItem("The first update no longer stops on the installing screen", "#7"), NotesItem("Short", null)),
            notes.single().items,
        )
    }
}
