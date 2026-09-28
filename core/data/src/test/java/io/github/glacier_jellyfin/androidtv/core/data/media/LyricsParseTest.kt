package io.github.glacier_jellyfin.androidtv.core.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsParseTest {

    @Test
    fun `synced lyrics keep their timed lines`() {
        val lyrics = parseLyrics(listOf(LyricLine("[ar: Someone]", null), LyricLine("One", 1_000), LyricLine("Two", 2_000)))!!
        assertTrue(lyrics.synced)
        assertEquals(listOf("One", "Two"), lyrics.lines.map { it.text })
    }

    @Test
    fun `plain lyrics stay in order without tags`() {
        val lyrics = parseLyrics(listOf(LyricLine("[ti: Title]", null), LyricLine("[Chorus]", null), LyricLine("Line", null)))!!
        assertFalse(lyrics.synced)
        assertEquals(listOf("[Chorus]", "Line"), lyrics.lines.map { it.text })
    }

    @Test
    fun `an instrumental tag marks the song instrumental`() {
        val lyrics = parseLyrics(listOf(LyricLine("[au: instrumental]", null)))!!
        assertTrue(lyrics.instrumental)
        assertTrue(lyrics.lines.isEmpty())
    }

    @Test
    fun `nothing but blanks is no lyrics`() {
        assertNull(parseLyrics(listOf(LyricLine(" ", null), LyricLine("[by: tool]", null))))
    }
}
