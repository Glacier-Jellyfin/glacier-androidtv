package io.github.glacier_jellyfin.androidtv.core.data

import androidx.datastore.core.CorruptionException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException

class EncryptedJsonSerializerTest {

    /** Reversible stand-in for the keystore; fails like GCM on tampered input. */
    private object XorCipher : PayloadCipher {
        override fun encrypt(plain: ByteArray) = byteArrayOf(MAGIC) + plain.map { (it.toInt() xor 0x5A).toByte() }
        override fun decrypt(encrypted: ByteArray): ByteArray {
            if (encrypted.firstOrNull() != MAGIC) throw GeneralSecurityException("bad tag")
            return encrypted.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
        const val MAGIC: Byte = 7
    }

    private val serializer = EncryptedJsonSerializer(XorCipher)

    private val state = AccountState(
        servers = listOf(StoredServer("s1", "Home", "http://home:8096", "12.1.0", lastUsedAt = 1)),
        users = listOf(StoredUser("s1", "u1", "Anna", null, accessToken = "secret-token", lastUsedAt = 1)),
        lastServerId = "s1",
    )

    @Test
    fun `round-trips the state without writing the token in plain text`() = runTest {
        val out = ByteArrayOutputStream()
        serializer.writeTo(state, out)
        assertFalse(out.toByteArray().decodeToString().contains("secret-token"))
        assertEquals(state, serializer.readFrom(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun `empty file yields the default state`() = runTest {
        assertEquals(AccountState(), serializer.readFrom(ByteArrayInputStream(ByteArray(0))))
    }

    @Test(expected = CorruptionException::class)
    fun `undecryptable file is reported as corrupt`() = runTest {
        serializer.readFrom(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
    }
}
