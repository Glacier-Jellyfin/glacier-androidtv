package io.github.glacier_jellyfin.androidtv.core.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseSelectorTest {

    private fun release(tag: String, prerelease: Boolean = tag.contains("-beta"), draft: Boolean = false, withApk: Boolean = true): Release {
        val version = tag.removePrefix("v")
        val assets = if (withApk) listOf(ReleaseAsset("glacier-androidtv-$version.apk", "https://example.invalid/$version", 1, null)) else emptyList()
        return Release(tag, isDraft = draft, isPrerelease = prerelease, assets = assets)
    }

    private val releases = listOf(
        release("v1.3.0"),
        release("v1.4.0-beta.1"),
        release("v1.4.0-beta.2"),
    )

    private fun v(text: String) = AppVersion.parse(text)!!

    @Test
    fun `stable channel ignores betas`() {
        assertEquals(v("1.3.0"), ReleaseSelector.select(releases, v("1.2.0"), UpdateChannel.Stable)?.version)
    }

    @Test
    fun `beta channel takes the newest release overall`() {
        assertEquals(v("1.4.0-beta.2"), ReleaseSelector.select(releases, v("1.3.0"), UpdateChannel.Beta)?.version)
    }

    @Test
    fun `never downgrades when switching from beta back to stable`() {
        assertNull(ReleaseSelector.select(releases, v("1.4.0-beta.2"), UpdateChannel.Stable))
    }

    @Test
    fun `stable release reaches beta users`() {
        val withStable = releases + release("v1.4.0")
        assertEquals(v("1.4.0"), ReleaseSelector.select(withStable, v("1.4.0-beta.2"), UpdateChannel.Beta)?.version)
    }

    @Test
    fun `skips drafts, releases without apk and mismatched prerelease flags`() {
        val broken = listOf(
            release("v2.0.0", draft = true),
            release("v2.1.0", withApk = false),
            release("v2.2.0", prerelease = true),
            release("v2.3.0-beta.1", prerelease = false),
        )
        assertNull(ReleaseSelector.select(broken, v("1.0.0"), UpdateChannel.Beta))
    }

    @Test
    fun `returns null when already current`() {
        assertNull(ReleaseSelector.select(releases, v("1.3.0"), UpdateChannel.Stable))
    }
}
