package io.github.glacier_jellyfin.androidtv.core.jellyfin

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for suspending work: a cancelled coroutine stays cancelled.
 * Leaving a screen cancels its loading, which is no failure to log or show,
 * so [CancellationException] is thrown on instead of ending up in the result.
 */
inline fun <T> runSuspendCatching(block: () -> T): Result<T> =
    runCatching(block).onFailure { if (it is CancellationException) throw it }
