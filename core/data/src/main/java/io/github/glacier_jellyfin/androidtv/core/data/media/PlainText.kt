package io.github.glacier_jellyfin.androidtv.core.data.media

private val LineBreaks = Regex("""(?i)<br\s*/?>|</(p|div|li|h[1-6])>""")
private val Tags = Regex("""<[^>]*>""")
private val Entities = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);""")
private val SpacesInLine = Regex("""[ \t ]+""")
private val BlankLines = Regex("""\n\s*\n+""")
private val NamedEntities = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "ndash" to "–", "mdash" to "—", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
)

/**
 * Text as the TV shows it: servers may keep HTML in overviews and biographies
 * (`<p>`, links, `&amp;`). Tags go, block ends become line breaks, entities are
 * decoded. Null when nothing readable is left.
 */
internal fun plainText(text: String?): String? {
    if (text.isNullOrBlank()) return null
    if ('<' !in text && '&' !in text) return text.trim()
    val stripped = text.replace("\r\n", "\n")
        .replace(LineBreaks, "\n")
        .replace(Tags, "")
        .replace(Entities) { match -> decode(match.groupValues[1]) ?: match.value }
    return stripped.lines()
        .joinToString("\n") { it.replace(SpacesInLine, " ").trim() }
        .replace(BlankLines, "\n\n")
        .trim()
        .takeIf { it.isNotEmpty() }
}

private fun decode(entity: String): String? = when {
    entity.startsWith("#x", ignoreCase = true) -> entity.drop(2).toIntOrNull(16)?.let(::codePoint)
    entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let(::codePoint)
    else -> NamedEntities[entity.lowercase()]
}

private fun codePoint(value: Int): String? =
    if (Character.isValidCodePoint(value)) String(Character.toChars(value)) else null
