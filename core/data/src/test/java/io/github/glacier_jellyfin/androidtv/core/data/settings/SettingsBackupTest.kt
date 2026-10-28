package io.github.glacier_jellyfin.androidtv.core.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {

    private val tv = DeviceSettings("tv", "Fire TV", savedAt = 1, ProfileSettings(appearance = AppearanceSettings(accent = AccentColor.Aurora)))
    private val shield = DeviceSettings("shield", "SHIELD", savedAt = 2, ProfileSettings(appearance = AppearanceSettings(compact = true)))

    // What the server adds to every answer, besides our own keys.
    private val serverDefaults = mapOf("chromecastVersion" to "stable", "skipBackLength" to "15000", "homesection0" to "smalllibrarytiles")

    @Test
    fun `copies survive a round trip, newest first`() {
        val prefs = withDeviceSettings(withDeviceSettings(serverDefaults, copy = tv), copy = shield)
        assertEquals(listOf(shield, tv), decodeDeviceSettings(prefs))
    }

    @Test
    fun `a write sends only the device copies back`() {
        assertEquals(setOf("device.tv"), withDeviceSettings(serverDefaults, copy = tv).keys)
    }

    @Test
    fun `saving again replaces the device's copy`() {
        val newer = tv.copy(savedAt = 5, settings = ProfileSettings())
        assertEquals(listOf(newer), decodeDeviceSettings(withDeviceSettings(withDeviceSettings(emptyMap(), copy = tv), copy = newer)))
    }

    @Test
    fun `deleting drops only that device's copy`() {
        val prefs = withDeviceSettings(withDeviceSettings(emptyMap(), copy = tv), copy = shield)
        assertEquals(listOf(shield), decodeDeviceSettings(withDeviceSettings(prefs, removeDeviceId = "tv")))
    }

    @Test
    fun `unreadable copies and other keys are skipped`() {
        val prefs = mapOf("device.broken" to "{not json", "device.empty" to null, "tvhome" to "") + withDeviceSettings(emptyMap(), copy = tv)
        assertEquals(listOf(tv), decodeDeviceSettings(prefs))
    }

    @Test
    fun `a copy with unknown options still loads`() {
        val prefs = mapOf("device.new" to """{"deviceId":"new","deviceName":"TV","savedAt":3,"future":1,"settings":{"appearance":{"accent":"NotYet"}}}""")
        val copy = decodeDeviceSettings(prefs).single()
        assertEquals(AppearanceSettings().accent, copy.settings.appearance.accent)
        assertTrue(copy.deviceName == "TV")
    }
}
