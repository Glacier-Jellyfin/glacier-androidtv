package io.github.glacier_jellyfin.androidtv.core.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    @Test
    fun `parses stable, beta and tag forms`() {
        assertEquals(AppVersion(1, 4, 0), AppVersion.parse("1.4.0"))
        assertEquals(AppVersion(1, 4, 0, beta = 2), AppVersion.parse("1.4.0-beta.2"))
        assertEquals(AppVersion(1, 4, 0, beta = 2), AppVersion.parse("v1.4.0-beta.2"))
    }

    @Test
    fun `rejects unsupported forms`() {
        listOf("1.4", "1.4.0-rc.1", "1.4.0-beta", "1.100.0", "1.4.0-beta.99", "1.4.0-beta.0", "latest")
            .forEach { assertNull(it, AppVersion.parse(it)) }
    }

    @Test
    fun `versionCode follows the agreed scheme`() {
        assertEquals(1_040_002, AppVersion.parse("1.4.0-beta.2")!!.versionCode)
        assertEquals(1_040_099, AppVersion.parse("1.4.0")!!.versionCode)
    }

    @Test
    fun `stable outranks its own betas and betas order by number`() {
        val beta1 = AppVersion.parse("1.4.0-beta.1")!!
        val beta2 = AppVersion.parse("1.4.0-beta.2")!!
        val stable = AppVersion.parse("1.4.0")!!
        assertTrue(beta1 < beta2)
        assertTrue(beta2 < stable)
        assertTrue(AppVersion.parse("1.3.9")!! < beta1)
    }

    @Test
    fun `toString round-trips`() {
        listOf("1.4.0", "1.4.0-beta.2", "0.1.0-beta.1").forEach {
            assertEquals(it, AppVersion.parse(it).toString())
        }
    }
}
