package io.github.glacier_jellyfin.androidtv.core.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException

/** Symmetric encryption of whole payloads, see [KeystoreCipher]. */
interface PayloadCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(encrypted: ByteArray): ByteArray
}

/**
 * Stores [AccountState] as encrypted JSON. A file that cannot be decrypted
 * (for example after the keystore key was lost) is reported as corrupt, and
 * the DataStore corruption handler resets it to an empty state: the user
 * signs in again rather than being locked out.
 */
class EncryptedJsonSerializer(private val cipher: PayloadCipher) : Serializer<AccountState> {

    private val json = Json { ignoreUnknownKeys = true }

    override val defaultValue: AccountState = AccountState()

    override suspend fun readFrom(input: InputStream): AccountState {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return defaultValue
        return try {
            json.decodeFromString(AccountState.serializer(), cipher.decrypt(bytes).decodeToString())
        } catch (e: GeneralSecurityException) {
            throw CorruptionException("Cannot decrypt account state", e)
        } catch (e: SerializationException) {
            throw CorruptionException("Cannot parse account state", e)
        }
    }

    override suspend fun writeTo(t: AccountState, output: OutputStream) {
        output.write(cipher.encrypt(json.encodeToString(AccountState.serializer(), t).encodeToByteArray()))
    }
}
