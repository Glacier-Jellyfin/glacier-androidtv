package io.github.glacier_jellyfin.androidtv.core.updater

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {

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
}
