package io.github.glacier_jellyfin.androidtv.core.data

import kotlinx.serialization.Serializable

/**
 * Everything Glacier remembers about servers and profiles on this device.
 * Persisted encrypted (see [EncryptedJsonSerializer]) because it holds
 * access tokens and PIN hashes.
 */
@Serializable
data class AccountState(
    val servers: List<StoredServer> = emptyList(),
    val users: List<StoredUser> = emptyList(),
    val lastServerId: String? = null,
)

@Serializable
data class StoredServer(
    val id: String,
    val name: String,
    val address: String,
    val version: String?,
    val lastUsedAt: Long,
)

@Serializable
data class StoredUser(
    val serverId: String,
    val userId: String,
    val name: String,
    val primaryImageTag: String?,
    /** Null after signing out: the profile stays listed but needs a password again. */
    val accessToken: String?,
    /** Local profile PIN; null when the profile has none. */
    val pin: PinHash? = null,
    val lastUsedAt: Long,
)

@Serializable
data class PinHash(
    val salt: String,
    val hash: String,
    val iterations: Int,
)
