package io.github.glacier_jellyfin.androidtv.core.jellyfin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerInfoTest {

    private fun server(version: String?) = ServerInfo(id = "id", name = "Home", address = "http://h:8096", version = version)

    @Test
    fun `12_0 and newer are supported`() {
        assertTrue(server("12.0.0").isSupported)
        assertTrue(server("12.1.0").isSupported)
        assertTrue(server("13.0.0").isSupported)
    }

    @Test
    fun `older or unknown versions are not supported`() {
        assertFalse(server("10.11.4").isSupported)
        assertFalse(server(null).isSupported)
        assertFalse(server("unknown").isSupported)
    }
}
