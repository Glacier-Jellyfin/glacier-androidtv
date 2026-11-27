package io.github.glacier_jellyfin.androidtv.navigation

import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.settings.NavigationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavEntriesTest {
    private val kinds = listOf(LibraryKind.Movies)

    @Test
    fun `activity is offered only when the feed is available`() {
        assertFalse(NAV_ACTIVITY in navEntryIds(kinds, liveTv = false, activity = false))
        assertEquals(NAV_ACTIVITY, navEntryIds(kinds, liveTv = false, activity = true).last())
    }

    @Test
    fun `activity is shown for settings saved without it`() {
        val targets = navTargets(NavigationSettings(), kinds, liveTv = false, activity = true)
        assertTrue(NavTarget.Activity in targets)
        assertFalse(NavTarget.Activity in navTargets(NavigationSettings(), kinds, liveTv = false, activity = false))
    }
}
