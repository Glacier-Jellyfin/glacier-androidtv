package io.github.glacier_jellyfin.androidtv.core.updater

import org.junit.Assert.assertEquals
import org.junit.Test

class ChangelogTest {

    private val markdown = """
        # Changelog

        All notable changes to Glacier.

        ## [Unreleased]

        ### New
        - Not released yet

        ## [0.6.2] - 2026-10-27

        ### Fixed
        - Profile picture ring

        ## [0.6.1] - 2026-10-22

        ### Improved
        - Faster libraries

        ## [0.6.0-beta.1] - 2026-10-20

        ### New
        - Live TV
    """.trimIndent()

    @Test
    fun `reads released versions and skips unreleased`() {
        val entries = Changelog.parse(markdown)
        assertEquals(listOf("0.6.2", "0.6.1", "0.6.0-beta.1"), entries.map { it.version.toString() })
        assertEquals("2026-10-27", entries.first().date)
        assertEquals("### Fixed\n- Profile picture ring", entries.first().body)
    }

    @Test
    fun `between keeps versions after the old one up to the new one`() {
        val entries = Changelog.parse(markdown)
        val changes = Changelog.between(entries, AppVersion.parse("0.6.0-beta.1")!!, AppVersion.parse("0.6.2")!!)
        assertEquals(listOf("0.6.2", "0.6.1"), changes.map { it.version.toString() })
    }
}
