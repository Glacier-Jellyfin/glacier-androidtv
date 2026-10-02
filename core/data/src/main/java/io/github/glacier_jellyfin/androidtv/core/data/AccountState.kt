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
    /** Which profile opens by itself when the app starts (Settings › System). */
    val startProfile: StartProfile = StartProfile(),
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
    /** Local profile PIN; null until one is set in Settings › Account. */
    val pin: PinHash? = null,
    val pinChangedAt: Long? = null,
    val protection: Protection = Protection(),
    val lastUsedAt: Long,
)

@Serializable
data class PinHash(
    val salt: String,
    val hash: String,
    val iterations: Int,
)

/**
 * Parental control of one profile on this device (Settings › Account). Kept
 * here rather than with the other settings so it sits next to the PIN in the
 * encrypted store.
 */
@Serializable
data class Protection(
    val maxAge: AgeLimit = AgeLimit.All,
    /** Titles without an age rating count as above [maxAge]. */
    val blockUnrated: Boolean = false,
    val pinOnProfileSwitch: Boolean = false,
    /** Titles above [maxAge] stay visible and open with the PIN; otherwise they are hidden. */
    val pinForLocked: Boolean = false,
    /** The Account settings (parental control, PIN, sign-out) open only with the PIN. */
    val pinForSettings: Boolean = false,
) {
    /** Some titles are above the limit, so lists have to be checked. */
    val restricts: Boolean get() = maxAge.age != null || blockUnrated
}

/** Highest age rating a profile may watch; [All] has no limit. */
@Serializable
enum class AgeLimit(val age: Int?) { A0(0), A6(6), A12(12), A16(16), All(null) }

/** Device-wide choice of the profile that opens by itself when the app starts. */
@Serializable
data class StartProfile(
    val mode: StartMode = StartMode.Single,
    /** The profile for [StartMode.Fixed]. */
    val serverId: String? = null,
    val userId: String? = null,
)

@Serializable
enum class StartMode {
    /** Always show "Who's watching?". */
    Picker,

    /** Open the profile when it is the only one on the server. */
    Single,

    /** Open the profile used last. */
    Last,

    /** Open one chosen profile. */
    Fixed,
}
