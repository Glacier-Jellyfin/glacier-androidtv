package io.github.glacier_jellyfin.androidtv.search

import org.junit.Assert.assertEquals
import org.junit.Test

class TypeCharTest {

    @Test
    fun `words start with a capital and continue in lower case`() {
        var query = ""
        "DRACULA 1931".forEach { query = if (it == ' ') "$query " else typeChar(query, it) }
        assertEquals("Dracula 1931", query)
    }
}
