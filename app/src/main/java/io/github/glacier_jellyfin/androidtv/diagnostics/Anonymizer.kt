package io.github.glacier_jellyfin.androidtv.diagnostics

import io.github.glacier_jellyfin.androidtv.core.data.AccountState

/**
 * Replaces what identifies the user in a log before it leaves the device:
 * server hosts become `server-1`, `server-2` (scheme and port stay, they
 * matter for connection problems), server and user names `server-1-name`,
 * `user-1`. Item and user ids stay: they only mean something on the server.
 */
class Anonymizer(servers: List<Pair<String, String>>, users: List<String>) {

    /** Longest text first, so the user "demo" does not break the host "demo.jellyfin.org". */
    private val replacements: List<Replacement> = buildList {
        servers.forEachIndexed { index, (address, name) ->
            val label = "server-${index + 1}"
            hostOf(address)?.let { add(Replacement(it, Regex(Regex.escape(it), RegexOption.IGNORE_CASE), label)) }
            if (name.usable()) add(Replacement(name, word(name), "$label-name"))
        }
        users.distinctBy { it.lowercase() }.filter { it.usable() }.forEachIndexed { index, name ->
            add(Replacement(name, word(name), "user-${index + 1}"))
        }
    }.sortedByDescending { it.text.length }

    fun apply(text: String): String = replacements.fold(text) { result, it -> it.pattern.replace(result, it.label) }

    private class Replacement(val text: String, val pattern: Regex, val label: String)

    companion object {
        fun of(state: AccountState) = Anonymizer(
            servers = state.servers.map { it.address to it.name },
            users = state.users.map { it.name },
        )

        /** "https://jf.example.org:8920/jellyfin" -> "jf.example.org". */
        internal fun hostOf(address: String): String? =
            address.substringAfter("://").substringBefore('/').substringBefore(':').takeIf { it.isNotBlank() }

        /** Whole words only, so "Jens" leaves "Jensen" alone. */
        private fun word(name: String) = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(name)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

        /**
         * Names this short, or Jellyfin's default server name, would replace
         * ordinary words of the log.
         */
        private fun String.usable() = length >= MIN_NAME_LENGTH && !equals("jellyfin", ignoreCase = true)

        private const val MIN_NAME_LENGTH = 3
    }
}
