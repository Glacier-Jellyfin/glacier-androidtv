package io.github.glacier_jellyfin.androidtv.core.log

/**
 * Removes secrets from log text before it is stored: access tokens in URLs
 * (`api_key=`, `ApiKey=`), Jellyfin's `Authorization: MediaBrowser … Token="…"`
 * header, bearer tokens, passwords and Quick Connect secrets.
 */
object Redaction {

    private const val HIDDEN = "<hidden>"

    private val KEY_VALUE = Regex(
        """(?i)\b(api_?key|access_?token|token|x-emby-token|x-mediabrowser-token|secret|password|pw)\b("?\s*[=:]\s*"?)([^"&\s,;]+)""",
    )

    private val BEARER = Regex("""(?i)\b(bearer\s+)\S+""")

    fun apply(text: String): String =
        text.replace(KEY_VALUE) { "${it.groupValues[1]}${it.groupValues[2]}$HIDDEN" }
            .replace(BEARER) { "${it.groupValues[1]}$HIDDEN" }
}
