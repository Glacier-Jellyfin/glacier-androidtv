package io.github.glacier_jellyfin.androidtv.core.data.settings

import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentAction
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentKind
import io.github.glacier_jellyfin.androidtv.core.data.playback.UpNextMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SettingsSerializerTest {

    @Test
    fun `settings survive a round trip`() = runTest {
        val state = SettingsState(
            profiles = mapOf(
                profileKey("server", "user") to ProfileSettings(
                    playback = PlaybackSettings(
                        upNext = UpNextChoice.S20,
                        maxBitrate = MaxBitrate.M1_5,
                        segments = mapOf(SegmentKind.Intro to SegmentAction.Skip),
                    ),
                    subtitleStyle = SubtitleStyle(size = SubtitleSize.XLarge, edge = SubtitleEdge.Outline),
                    appearance = AppearanceSettings(accent = AccentColor.Aurora, compact = true, groupCollections = false),
                    home = HomeSettings(spotlightSource = SpotlightSource.Random, spotlightCount = SpotlightCount.N10),
                ),
            ),
        )
        val out = ByteArrayOutputStream()
        SettingsSerializer.writeTo(state, out)
        assertEquals(state, SettingsSerializer.readFrom(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun `unknown options fall back to their defaults`() = runTest {
        val json = """{"profiles":{"s/u":{"playback":{"upNext":"S90","seekBack":"S5"},"future":1}}}"""
        val playback = SettingsSerializer.readFrom(ByteArrayInputStream(json.toByteArray())).profiles.getValue("s/u").playback
        assertEquals(UpNextChoice.WithCredits, playback.upNext)
        assertEquals(SeekStep.S5, playback.seekBack)
    }

    @Test
    fun `settings written before appearance and home existed get their defaults`() = runTest {
        val json = """{"profiles":{"s/u":{"playback":{"seekBack":"S5"}}}}"""
        val profile = SettingsSerializer.readFrom(ByteArrayInputStream(json.toByteArray())).profiles.getValue("s/u")
        assertEquals(AppearanceSettings(), profile.appearance)
        assertEquals(HomeSettings(), profile.home)
    }

    @Test
    fun `segment settings keep defaults for kinds not stored`() {
        val policy = PlaybackSettings(segments = mapOf(SegmentKind.Intro to SegmentAction.Skip), upNext = UpNextChoice.Off).segmentPolicy
        assertEquals(SegmentAction.Skip, policy.action(SegmentKind.Intro))
        assertEquals(SegmentAction.Skip, policy.action(SegmentKind.Recap))
        assertEquals(UpNextMode.Off, policy.upNext)
    }
}
