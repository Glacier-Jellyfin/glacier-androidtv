package io.github.glacier_jellyfin.androidtv.settings

import io.github.glacier_jellyfin.androidtv.core.data.geodata.GeoDataState
import io.github.glacier_jellyfin.androidtv.core.data.geodata.GeoFailure
import io.github.glacier_jellyfin.androidtv.core.data.geodata.GeoPacks
import java.text.NumberFormat
import java.util.Locale

/** What Settings says about the "Place names" setting; see [placeNamesStatus]. */
internal sealed interface PlaceNamesStatus {
    data object Off : PlaceNamesStatus

    /** The built-in places work. */
    data object Builtin : PlaceNamesStatus

    /** The files are being made ready; nothing is downloaded. */
    data object Preparing : PlaceNamesStatus

    data class Downloading(val bytes: Long, val total: Long) : PlaceNamesStatus {
        /** Whole percent, 0..100. */
        val percent: Int get() = if (total <= 0) 0 else (bytes * 100 / total).toInt().coerceIn(0, 100)

        /** 0..1 for the progress bar; null while the size is unknown (no bar then). */
        val fraction: Float? get() = if (total <= 0) null else (bytes.toFloat() / total).coerceIn(0f, 1f)
    }

    data class Ready(val bytesOnDisk: Long) : PlaceNamesStatus

    data class Failed(val reason: GeoFailure, val usingFallback: Boolean) : PlaceNamesStatus
}

/**
 * The status of the chosen [level] (null: off) from the manager's [state]. A state of another level is a change that
 * has not arrived yet; the built-in pack being unpacked is "preparing", not a download.
 */
internal fun placeNamesStatus(level: Int?, state: GeoDataState): PlaceNamesStatus {
    if (level == null) return PlaceNamesStatus.Off
    val stateLevel = when (state) {
        GeoDataState.Off -> null
        is GeoDataState.Downloading -> state.level
        is GeoDataState.Ready -> state.level
        is GeoDataState.Failed -> state.level
    }
    if (stateLevel != level) return PlaceNamesStatus.Preparing
    val bundled = level == GeoPacks.BUNDLED_LEVEL
    return when (state) {
        GeoDataState.Off -> PlaceNamesStatus.Preparing

        is GeoDataState.Downloading ->
            if (bundled) PlaceNamesStatus.Preparing else PlaceNamesStatus.Downloading(state.bytes, state.total)

        is GeoDataState.Ready ->
            if (bundled) PlaceNamesStatus.Builtin else PlaceNamesStatus.Ready(state.bytesOnDisk)

        is GeoDataState.Failed -> PlaceNamesStatus.Failed(state.reason, state.usingFallback)
    }
}

private const val BYTES_PER_MB = 1_000_000.0

/** Megabytes as the update card counts them (10^6 bytes), without a unit: one decimal below 10, else whole. */
internal fun megabytesNumber(bytes: Long, locale: Locale): String {
    val mb = bytes / BYTES_PER_MB
    val format = NumberFormat.getNumberInstance(locale)
    format.maximumFractionDigits = if (mb < 10) 1 else 0
    format.minimumFractionDigits = format.maximumFractionDigits
    format.isGroupingUsed = false
    return format.format(mb)
}

/** Megabytes with one decimal always, for a progress such as "12.3 of 57 MB". */
internal fun progressMegabytes(bytes: Long, locale: Locale): String {
    val format = NumberFormat.getNumberInstance(locale)
    format.maximumFractionDigits = 1
    format.minimumFractionDigits = 1
    format.isGroupingUsed = false
    return format.format(bytes / BYTES_PER_MB)
}

/** The levels the setting offers: off first (null), then the packs coarsest first, all places last. */
internal fun placeNamesChoices(): List<Int?> = listOf<Int?>(null) +
    GeoPacks.ALL.map { it.level }.filter { it != 0 }.sortedDescending() + 0
