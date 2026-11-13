package io.github.glacier_jellyfin.androidtv.setup

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Saved servers that did not answer lately; the server list shows them as not reachable before its own check is done. */
@Singleton
class ServerReachability @Inject constructor() {
    private val unreachable = MutableStateFlow<Set<String>>(emptySet())

    fun isReachable(serverId: String): Boolean = serverId !in unreachable.value

    fun report(serverId: String, reachable: Boolean) = unreachable.update {
        if (reachable) {
            it - serverId
        } else {
            it +
                serverId
        }
    }
}
