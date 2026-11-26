package io.github.glacier_jellyfin.androidtv.core.data.media

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataLanguageTest {

    private val a = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val b = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private fun folders(vararg entries: Pair<UUID, String?>) =
        entries.associate { (id, language) -> id.toString().replace("-", "") to language }

    @Test
    fun `libraries that agree share their language`() {
        val folders = folders(a to "de", b to "de-DE")
        assertEquals("de", MetadataLanguage.sharedLanguage(folders, listOf(a, b)))
        assertEquals("de", MetadataLanguage.sharedLanguage(folders, listOf(a)))
    }

    @Test
    fun `libraries that disagree or have none share nothing`() {
        assertNull(MetadataLanguage.sharedLanguage(folders(a to "de", b to "fr"), listOf(a, b)))
        assertNull(MetadataLanguage.sharedLanguage(folders(a to "de", b to null), listOf(a, b)))
        assertNull(MetadataLanguage.sharedLanguage(folders(a to ""), listOf(a)))
        assertNull(MetadataLanguage.sharedLanguage(folders(a to "de"), listOf(b)))
    }

    @Test
    fun `nothing readable or nothing asked shares nothing`() {
        assertNull(MetadataLanguage.sharedLanguage(null, listOf(a)))
        assertNull(MetadataLanguage.sharedLanguage(folders(a to "de"), emptyList()))
    }
}
