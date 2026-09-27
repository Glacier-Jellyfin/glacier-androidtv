package io.github.glacier_jellyfin.androidtv.core.jellyfin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.PublicSystemInfo
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.measureTimedValue

sealed interface ResolveResult {
    data class Found(val server: ServerInfo) : ResolveResult

    /** No candidate address answered like a Jellyfin server. */
    data object NotFound : ResolveResult
}

@Singleton
class ServerFinder @Inject constructor(
    private val jellyfin: Jellyfin,
) {

    /** Servers answering the Jellyfin UDP broadcast on the local network, probed one by one. */
    fun discover(): Flow<DiscoveredServer> =
        jellyfin.discovery.discoverLocalServers().mapNotNull { probe(it.address) }

    /**
     * Turns user input such as "jellyfin.example.com" or "192.168.1.10" into a
     * working server address. Candidates are tried in the SDK's order, most
     * secure first: HTTPS before HTTP, Jellyfin ports before protocol defaults.
     * The first candidate that answers as a Jellyfin server wins; its version
     * is checked by the caller so older servers can be offered with a warning.
     */
    suspend fun resolve(input: String): ResolveResult = withContext(Dispatchers.IO) { resolveBlocking(input) }

    private suspend fun resolveBlocking(input: String): ResolveResult {
        val candidates = jellyfin.discovery.getAddressCandidates(input)
        val answers = jellyfin.discovery.getRecommendedServers(candidates)
        val byAddress = answers.associateBy { it.address }
        for (address in candidates) {
            val info = byAddress[address]?.systemInfo?.getOrNull() ?: continue
            info.toServerInfo(address)?.let { return ResolveResult.Found(it) }
        }
        return ResolveResult.NotFound
    }

    /** Checks a known address; null when it does not answer as a Jellyfin server. */
    suspend fun probe(address: String): DiscoveredServer? = withContext(Dispatchers.IO) { probeBlocking(address) }

    private suspend fun probeBlocking(address: String): DiscoveredServer? {
        val api = jellyfin.createApi(baseUrl = address)
        val (info, latency) = runCatching {
            measureTimedValue { api.systemApi.getPublicSystemInfo().content }
        }.getOrNull() ?: return null
        val server = info.toServerInfo(address) ?: return null
        val users = runCatching { api.userApi.getPublicUsers().content.size }.getOrNull()
        return DiscoveredServer(server, users, latency.inWholeMilliseconds)
    }

    private fun PublicSystemInfo.toServerInfo(address: String): ServerInfo? {
        val serverId = id ?: return null
        if (productName != null && !productName.equals("Jellyfin Server", ignoreCase = true)) return null
        return ServerInfo(
            id = serverId,
            name = serverName?.takeIf { it.isNotBlank() } ?: address,
            address = address,
            version = version,
        )
    }
}
