package io.github.glacier_jellyfin.androidtv.core.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvSettingsTest {

    @Test
    fun `without marks the server decides`() {
        assertTrue(LiveTvSettings().isRadio("a", serverSaysRadio = true))
        assertFalse(LiveTvSettings().isRadio("a", serverSaysRadio = false))
    }

    @Test
    fun `a channel heard without a picture is radio`() {
        assertTrue(LiveTvSettings().heardAsRadio("a").isRadio("a", serverSaysRadio = false))
    }

    @Test
    fun `moved to TV by hand it stays TV`() {
        val marks = LiveTvSettings().heardAsRadio("a").moved("a", toRadio = false)
        assertFalse(marks.isRadio("a", serverSaysRadio = true))
        assertEquals(marks, marks.heardAsRadio("a"))
    }

    @Test
    fun `moved to radio by hand it is radio again`() {
        val marks = LiveTvSettings().moved("a", toRadio = false).moved("a", toRadio = true)
        assertTrue(marks.isRadio("a", serverSaysRadio = false))
        assertEquals(emptySet<String>(), marks.tv)
    }
}
