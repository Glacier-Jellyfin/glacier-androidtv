package io.github.glacier_jellyfin.androidtv.core.designsystem

/** One key of the on-screen keyboard. [span] is its width in grid columns (8 per row). */
sealed interface Key {
    val span: Int

    data class Char(val value: kotlin.Char, override val span: Int = 1) : Key
    data object Space : Key { override val span = 3 }
    data object Backspace : Key { override val span = 1 }
    data object Shift : Key { override val span = 1 }
    data object Symbols : Key { override val span = 1 }
    data object SystemKeyboard : Key { override val span = 2 }
}

/**
 * The design's 8-column keyboard, extended with shift and a symbol layer so
 * that passwords can be typed. Letters are lower case unless shift is on.
 */
object KeyboardLayout {
    const val Columns = 8

    private val letters = listOf("abcdefgh", "ijklmnop", "qrstuvwx", "yz012345", "6789.:/-")
    private val symbols = listOf("!@#\$%&*?", "()[]{}<>", "+=~^'\",;", "_\\012345", "6789.:/-")

    fun rows(shift: Boolean, symbolLayer: Boolean): List<List<Key>> {
        val source = if (symbolLayer) symbols else letters
        val characterRows = source.map { row ->
            row.map { c -> Key.Char(if (shift && !symbolLayer) c.uppercaseChar() else c) }
        }
        return characterRows + listOf(listOf(Key.Shift, Key.Symbols, Key.Backspace, Key.Space, Key.SystemKeyboard))
    }
}

/** Applies a key to [text]. Returns null for keys that do not edit text. */
fun KeyboardLayout.apply(text: String, key: Key): String? = when (key) {
    is Key.Char -> text + key.value
    Key.Space -> "$text "
    Key.Backspace -> text.dropLast(1)
    Key.Shift, Key.Symbols, Key.SystemKeyboard -> null
}
