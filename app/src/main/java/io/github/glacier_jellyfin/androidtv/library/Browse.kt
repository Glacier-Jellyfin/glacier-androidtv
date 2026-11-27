package io.github.glacier_jellyfin.androidtv.library

/** The viewer's list and the position in it, for the pure steps of deleting one. */
internal data class Browse<T>(val items: List<T>, val index: Int)

/**
 * Without the item at [at], staying at the same position: that is the next one, or the previous one when the
 * last was removed. A position before the current one moves it back so the same item stays shown.
 */
internal fun <T> Browse<T>.without(at: Int): Browse<T> {
    if (at !in items.indices) return this
    val rest = items.filterIndexed { i, _ -> i != at }
    val moved = if (at < index) index - 1 else index
    return Browse(rest, moved.coerceIn(0, (rest.size - 1).coerceAtLeast(0)))
}

/**
 * [item] back at [at] (at the end when the list got shorter). The item shown stays shown, so a position at or
 * after [at] moves on by one.
 */
internal fun <T> Browse<T>.with(item: T, at: Int): Browse<T> {
    val position = at.coerceIn(0, items.size)
    val moved = if (items.isNotEmpty() && position <= index) index + 1 else index
    return Browse(items.toMutableList().apply { add(position, item) }, moved)
}
