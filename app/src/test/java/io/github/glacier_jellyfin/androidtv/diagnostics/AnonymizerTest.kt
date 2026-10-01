package io.github.glacier_jellyfin.androidtv.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class AnonymizerTest {

    private val anonymizer = Anonymizer(
        servers = listOf("https://jf.example.org" to "Home Media", "http://192.168.1.20:8096" to "Jellyfin"),
        users = listOf("Jens", "jens", "Al", "example"),
    )

    @Test
    fun `replaces hosts but keeps scheme and port`() {
        assertEquals(
            "GET https://server-1/Users/1f2e failed; fallback http://server-2:8096",
            anonymizer.apply("GET https://JF.example.org/Users/1f2e failed; fallback http://192.168.1.20:8096"),
        )
    }

    @Test
    fun `replaces user and server names as whole words`() {
        assertEquals(
            "Signed in as user-1 on server-1-name; Jensen stays",
            anonymizer.apply("Signed in as jens on Home Media; Jensen stays"),
        )
    }

    @Test
    fun `replaces a host before a user name inside it`() {
        assertEquals("https://server-1 for user-2", anonymizer.apply("https://jf.example.org for example"))
    }

    @Test
    fun `leaves short names and the default server name alone`() {
        assertEquals("Al plays on Jellyfin 12.1", anonymizer.apply("Al plays on Jellyfin 12.1"))
    }

    @Test
    fun `reads the host of an address`() {
        assertEquals("jf.example.org", Anonymizer.hostOf("https://jf.example.org:8920/jellyfin"))
        assertEquals("192.168.1.20", Anonymizer.hostOf("192.168.1.20:8096"))
    }
}
