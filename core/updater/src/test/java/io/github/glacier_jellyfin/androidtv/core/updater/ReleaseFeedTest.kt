package io.github.glacier_jellyfin.androidtv.core.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseFeedTest {

    @Test
    fun `maps the GitHub releases response`() {
        val releases = ReleaseFeed.parse(
            """
            [
              {
                "tag_name": "v1.4.0-beta.2",
                "draft": false,
                "prerelease": true,
                "published_at": "2026-09-26T10:00:00Z",
                "body": "### New\n- Voice search",
                "author": { "login": "someone" },
                "assets": [
                  {
                    "name": "glacier-androidtv-1.4.0-beta.2.apk",
                    "browser_download_url": "https://github.com/x/y/releases/download/v1.4.0-beta.2/glacier-androidtv-1.4.0-beta.2.apk",
                    "size": 26319872,
                    "digest": "sha256:abc"
                  }
                ]
              },
              { "tag_name": "v1.3.0", "body": null, "assets": [] }
            ]
            """.trimIndent(),
        )
        val beta = releases[0]
        assertEquals("v1.4.0-beta.2", beta.tag)
        assertEquals(true, beta.isPrerelease)
        assertEquals("2026-09-26T10:00:00Z", beta.publishedAt)
        assertEquals("### New\n- Voice search", beta.body)
        assertEquals(
            ReleaseAsset(
                "glacier-androidtv-1.4.0-beta.2.apk",
                "https://github.com/x/y/releases/download/v1.4.0-beta.2/glacier-androidtv-1.4.0-beta.2.apk",
                26319872,
                "sha256:abc",
            ),
            beta.assets.single(),
        )
        assertEquals("", releases[1].body)
        assertNull(releases[1].publishedAt)
    }

    @Test
    fun `selected release keeps its notes`() {
        val releases = ReleaseFeed.parse(
            """[{ "tag_name": "v1.3.0", "body": "- Fix", "assets": [{ "name": "glacier-androidtv-1.3.0.apk", "browser_download_url": "u", "size": 1 }] }]""",
        )
        val candidate = ReleaseSelector.select(releases, AppVersion(1, 2, 0), UpdateChannel.Stable)
        assertEquals("- Fix", candidate?.release?.body)
        assertNull(candidate?.apk?.digest)
    }
}
