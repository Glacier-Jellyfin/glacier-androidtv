package io.github.glacier_jellyfin.androidtv.core.log

import org.junit.Assert.assertEquals
import org.junit.Test

class RedactionTest {

    @Test
    fun `hides tokens in URLs`() {
        assertEquals(
            "https://jf.example.org/Videos/1/stream?static=true&api_key=<hidden>&MediaSourceId=2",
            Redaction.apply("https://jf.example.org/Videos/1/stream?static=true&api_key=0123abcd&MediaSourceId=2"),
        )
        assertEquals("/Audio/1/universal?ApiKey=<hidden>", Redaction.apply("/Audio/1/universal?ApiKey=deadbeef"))
    }

    @Test
    fun `hides the token of the Jellyfin authorization header`() {
        assertEquals(
            """MediaBrowser Client="Glacier", DeviceId="abc", Version="0.1.0", Token="<hidden>"""",
            Redaction.apply("""MediaBrowser Client="Glacier", DeviceId="abc", Version="0.1.0", Token="s3cr3t""""),
        )
        assertEquals("X-Emby-Token: <hidden>", Redaction.apply("X-Emby-Token: s3cr3t"))
        assertEquals("Authorization: Bearer <hidden>", Redaction.apply("Authorization: Bearer s3cr3t"))
    }

    @Test
    fun `hides passwords and Quick Connect secrets`() {
        assertEquals("{\"Pw\":\"<hidden>\"}", Redaction.apply("{\"Pw\":\"hunter2\"}"))
        assertEquals("secret=<hidden>", Redaction.apply("secret=0a1b2c"))
    }

    @Test
    fun `leaves other text alone`() {
        val text = "Playback failed: HTTP 500 for item 1f2e3d, tokens left 3"
        assertEquals(text, Redaction.apply(text))
    }
}
