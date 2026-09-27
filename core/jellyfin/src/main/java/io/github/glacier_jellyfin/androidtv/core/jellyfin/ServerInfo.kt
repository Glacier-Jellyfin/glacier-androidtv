package io.github.glacier_jellyfin.androidtv.core.jellyfin

import org.jellyfin.sdk.model.ServerVersion

/** Oldest server version Glacier supports. Older servers can still be used after confirmation. */
val MinimumServerVersion = ServerVersion(12, 0, 0)

/** A reachable Jellyfin server, as reported by its public system info. */
data class ServerInfo(
    val id: String,
    val name: String,
    val address: String,
    /** Version string as reported by the server, e.g. "12.1.0". */
    val version: String?,
) {
    val parsedVersion: ServerVersion? get() = version?.let(ServerVersion::fromString)

    /** False when the version is unknown or older than [MinimumServerVersion]. */
    val isSupported: Boolean get() = parsedVersion?.let { it >= MinimumServerVersion } ?: false
}

/** A server found on the local network, with the extra details the server list shows. */
data class DiscoveredServer(
    val server: ServerInfo,
    /** Number of users visible on the login screen, null when the server did not tell. */
    val publicUserCount: Int?,
    val latencyMillis: Long,
)
