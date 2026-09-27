package io.github.glacier_jellyfin.androidtv.core.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Four-digit PINs. Hashing cannot make 10,000 combinations hard to guess;
 * it only keeps the PIN itself out of the (already encrypted) state file.
 * The protection against guessing is that the PIN only unlocks this device.
 */
object Pins {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private val PIN_FORMAT = Regex("""\d{4}""")

    fun isValid(pin: String): Boolean = PIN_FORMAT.matches(pin)

    fun hash(pin: String, salt: ByteArray = randomSalt(), iterations: Int = ITERATIONS): PinHash {
        require(isValid(pin)) { "A PIN has exactly four digits" }
        return PinHash(
            salt = Base64.getEncoder().encodeToString(salt),
            hash = Base64.getEncoder().encodeToString(derive(pin, salt, iterations)),
            iterations = iterations,
        )
    }

    fun verify(pin: String, stored: PinHash): Boolean {
        if (!isValid(pin)) return false
        val salt = Base64.getDecoder().decode(stored.salt)
        val expected = Base64.getDecoder().decode(stored.hash)
        return MessageDigest.isEqual(derive(pin, salt, stored.iterations), expected)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun randomSalt() = ByteArray(16).also(SecureRandom()::nextBytes)
}
