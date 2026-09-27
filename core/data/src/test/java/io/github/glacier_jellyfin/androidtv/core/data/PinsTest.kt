package io.github.glacier_jellyfin.androidtv.core.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinsTest {

    @Test
    fun `accepts exactly four digits`() {
        assertTrue(Pins.isValid("0042"))
        listOf("", "123", "12345", "12a4", " 1234").forEach { assertFalse(it, Pins.isValid(it)) }
    }

    @Test
    fun `verifies the right pin only`() {
        val hash = Pins.hash("2580", iterations = 1_000)
        assertTrue(Pins.verify("2580", hash))
        assertFalse(Pins.verify("2581", hash))
        assertFalse(Pins.verify("25800", hash))
    }

    @Test
    fun `salts differ between hashes of the same pin`() {
        assertNotEquals(Pins.hash("1111", iterations = 1_000).salt, Pins.hash("1111", iterations = 1_000).salt)
    }
}
