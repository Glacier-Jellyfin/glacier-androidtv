package io.github.glacier_jellyfin.androidtv.core.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseSelectorTest {

    private fun release(
        tag: String,
        prerelease: Boolean = tag.contains("-beta"),
        draft: Boolean = false,
        apkNames: List<String> = listOf(ReleaseSelector.APK_NAME),
    ): Release {
        val version = tag.removePrefix("v")
        val assets = apkNames.map { ReleaseAsset(it, "https://example.invalid/$version/$it", 1, null) }
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
            release("v2.1.0", apkNames = emptyList()),
            release("v2.2.0", prerelease = true),
            release("v2.3.0-beta.1", prerelease = false),
        )
        assertNull(ReleaseSelector.select(broken, v("1.0.0"), UpdateChannel.Beta))
    }

    @Test
    fun `accepts the versioned apk name of older releases`() {
        val legacy = listOf(release("v1.5.0", apkNames = listOf("glacier-androidtv-1.5.0.apk")))
        assertEquals("glacier-androidtv-1.5.0.apk", ReleaseSelector.select(legacy, v("1.3.0"), UpdateChannel.Stable)?.apk?.name)
    }

    @Test
    fun `prefers the fixed apk name when a release has both`() {
        val both = listOf(release("v1.5.0", apkNames = listOf("glacier-androidtv-1.5.0.apk", ReleaseSelector.APK_NAME)))
        assertEquals(ReleaseSelector.APK_NAME, ReleaseSelector.select(both, v("1.3.0"), UpdateChannel.Stable)?.apk?.name)
    }

    @Test
    fun `ignores apks of other versions`() {
        val wrong = listOf(release("v1.5.0", apkNames = listOf("glacier-androidtv-1.4.0.apk")))
        assertNull(ReleaseSelector.select(wrong, v("1.3.0"), UpdateChannel.Stable))
    }

    @Test
    fun `returns null when already current`() {
        assertNull(ReleaseSelector.select(releases, v("1.3.0"), UpdateChannel.Stable))
    }

    @Test
    fun `brings the notes of every skipped release of the channel, newest first`() {
        val history = listOf(
            release("v1.5.0"),
            release("v1.4.1", apkNames = emptyList()),
            release("v1.4.0"),
            release("v1.3.0"),
            release("v1.5.0-beta.1"),
        )
        val candidate = ReleaseSelector.select(history, v("1.3.0"), UpdateChannel.Stable)!!
        assertEquals(listOf("v1.5.0", "v1.4.1", "v1.4.0"), candidate.changes.map { it.tag })
        assertEquals(emptyList<ReleaseAsset>(), candidate.skipped.flatMap { it.assets })
    }

    @Test
    fun `a newer release without apk is not offered`() {
        val history = listOf(release("v1.5.0", apkNames = emptyList()), release("v1.4.0"))
        val candidate = ReleaseSelector.select(history, v("1.3.0"), UpdateChannel.Stable)!!
        assertEquals(listOf("v1.4.0"), candidate.changes.map { it.tag })
    }
}
