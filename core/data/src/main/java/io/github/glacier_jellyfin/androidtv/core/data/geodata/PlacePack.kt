@file:Suppress("MagicNumber") // byte offsets of the binary format

package io.github.glacier_jellyfin.androidtv.core.data.geodata

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A place near a photo. [name] and [admin1] are in the language asked for when the pack has it. */
data class Place(
    val name: String,
    val admin1: String?,
    /** ISO 3166-1 alpha-2, upper case; null when the pack has none. */
    val countryCode: String?,
    /** [name] as stored by GeoNames, whatever the language asked for. */
    val plainName: String = name,
    /** [admin1] as stored by GeoNames. */
    val plainAdmin1: String? = admin1,
)

/** The pack file is not a valid pack of format v1; the manager deletes it and downloads it again. */
class BrokenPackException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A place pack of binary format v1 (`places-<level>.bin`, the unpacked `.gz` release asset), read in place from a
 * memory-mapped file. All integers are little-endian and every section starts at a 4-byte aligned offset.
 *
 * Header, 64 bytes at offset 0:
 *
 * | off | type | field |
 * |---|---|---|
 * | 0 | 4 bytes | magic `GLPL` |
 * | 4 | u16 | version = 1 |
 * | 6 | u16 | flags = 0 |
 * | 8 | u32 | minPopulation (the level) |
 * | 12 | u32 | dataDate, yyyymmdd |
 * | 16 | u32 | entryCount |
 * | 20 | u32 | admin1Count |
 * | 24 | u32 | localizedCount |
 * | 28 | u32 | languageCount |
 * | 32 | u32 | languagesOffset |
 * | 36 | u32 | cellIndexOffset |
 * | 40 | u32 | entriesOffset |
 * | 44 | u32 | admin1Offset |
 * | 48 | u32 | localizedOffset |
 * | 52 | u32 | namesOffset |
 * | 56 | u32 | namesSize in bytes |
 * | 60 | u32 | reserved = 0 |
 *
 * Sections:
 * - Languages, languageCount x 4 bytes: 2 ASCII bytes ISO 639-1 code and 2 zero bytes (`en`, `de`, `es`). A language
 *   index is a position in this table.
 * - Cell index, (180 * 360 + 1) x u32, a grid of 1 degree cells with
 *   `cell(lat, lon) = (floor(lat) + 90) * 360 + (floor(lon) + 180)`, lat clamped to [-90, 89.99999] and lon
 *   normalised to [-180, 180). `cellIndex[c]` is the index of the first entry of cell c, `cellIndex[64800]` is
 *   entryCount; the entries of cell c are `[cellIndex[c], cellIndex[c + 1])`.
 * - Entries, entryCount x 16 bytes, sorted by (cell, lat, lon, geonameid): i32 latitude and i32 longitude in 1e-5
 *   degrees, 2 bytes ASCII country code (zero bytes if missing), u16 admin1 index (0xFFFF = none), u32 offset of the
 *   name in the name blob.
 * - Admin1 table, admin1Count x 8 bytes: 2 bytes country code, u16 reserved, u32 name offset.
 * - Localized names, localizedCount x 8 bytes sorted by key: u32 key = `(target shl 2) or languageIndex` with target
 *   the entry index for a place and `entryCount + admin1 index` for an admin1 region; u32 name offset. A record
 *   exists only where the localized name differs from the stored GeoNames name.
 * - Name blob: strings of a u16 byte length and UTF-8 bytes; offsets point at the length prefix.
 *
 * Lookup: nearest entry by haversine (radius 6371.0088 km) over the cell of the query and its neighbours, widening
 * column by column; nothing farther than [MAX_KM]. Longitude wraps at the antimeridian, near the poles (above 89
 * degrees) the whole row of cells is searched. On equal distances the lower entry index wins.
 *
 * Create one with [open] or [of]; [nearest] may be called from any thread.
 */
class PlacePack private constructor(private val buf: ByteBuffer, private val layout: Layout) {

    /** The pack's own minimum number of inhabitants. */
    val minPopulation: Int get() = layout.minPopulation

    /**
     * The place nearest to the point, or null when none is within [MAX_KM]. [language] is an ISO 639-1 code such as
     * `de`; the GeoNames names are used where the pack has no name in it. Throws [BrokenPackException] when the data
     * contradicts itself.
     */
    @Suppress("CyclomaticComplexMethod") // one pass over the cells, split up it would only be harder to follow
    fun nearest(
        lat: Double,
        lon: Double,
        language: String,
    ): Place? {
        if (lat.isNaN() || lon.isNaN()) return null
        val qLat = lat.coerceIn(-90.0, MAX_LAT)
        val qLon = normalizeLon(lon)
        // The cell the way the writer computes it, from coordinates rounded to 1e-5 degrees.
        val row = (Math.floorDiv(Math.round(qLat * SCALE), SCALE.toLong()).toInt() + 90).coerceIn(0, ROWS - 1)
        val col = (Math.floorDiv(Math.round(qLon * SCALE), SCALE.toLong()).toInt() + 180).coerceIn(0, COLS - 1)

        val reach = min(abs(qLat) + 2.0, 89.99)
        val cosReach = cos(Math.toRadians(reach))
        // Columns that can hold something within MAX_KM, with one to spare.
        val colSpan = if (reach > POLE_LAT) COLS else ceil(MAX_KM / (KM_PER_DEGREE * cosReach)).toInt() + 1

        var best = Double.MAX_VALUE
        var bestIndex = -1
        fun scan(r: Int, c: Int) {
            val first = cellIndex(r * COLS + c)
            val end = cellIndex(r * COLS + c + 1)
            for (i in first until end) {
                val d = haversineKm(qLat, qLon, entryLat(i), entryLon(i))
                if (d < best || (d == best && i < bestIndex)) {
                    best = d
                    bestIndex = i
                }
            }
        }
        val rows = max(0, row - 1)..min(ROWS - 1, row + 1)
        if (colSpan >= COLS / 2) {
            for (r in rows) for (c in 0 until COLS) scan(r, c)
        } else {
            for (k in 0..colSpan) {
                for (r in rows) {
                    scan(r, Math.floorMod(col + k, COLS))
                    if (k > 0) scan(r, Math.floorMod(col - k, COLS))
                }
                // Cells k + 1 columns away are at least k degrees of longitude away.
                val nearestNext = k * KM_PER_DEGREE * cosReach * SAFETY
                if (nearestNext > min(best, MAX_KM)) break
            }
        }
        if (bestIndex < 0 || best > MAX_KM) return null
        return place(bestIndex, language)
    }

    private fun place(index: Int, language: String): Place {
        val lang = languageIndex(language)
        val base = layout.entries + index * ENTRY_SIZE
        val adminRaw = u16(base + 10)
        val admin = if (adminRaw == NO_ADMIN) null else adminRaw
        if (admin != null && admin >= layout.admin1Count) throw BrokenPackException("admin1 index $admin out of range")
        val plainName = name(u32(base + 12))
        val plainAdmin1 = admin?.let { name(u32(layout.admin1 + it * ADMIN_SIZE + 4)) }
        val name = localized(index.toLong(), lang) ?: plainName
        val admin1 = admin?.let { localized(layout.entryCount + it.toLong(), lang) } ?: plainAdmin1
        return Place(name, admin1, countryCode(base + 8), plainName, plainAdmin1)
    }

    private fun countryCode(offset: Int): String? {
        val a = buf.get(offset).toInt()
        val b = buf.get(offset + 1).toInt()
        if (a == 0 || b == 0) return null
        return "${a.toChar()}${b.toChar()}"
    }

    private fun languageIndex(language: String): Int {
        if (language.length != 2) return -1
        for (i in 0 until layout.languageCount) {
            val at = layout.languages + i * 4
            if (buf.get(at).toInt().toChar() == language[0].lowercaseChar() &&
                buf.get(at + 1).toInt().toChar() == language[1].lowercaseChar()
            ) {
                return i
            }
        }
        return -1
    }

    /** The localized name of [target] in language index [lang], or null. */
    private fun localized(target: Long, lang: Int): String? {
        if (lang < 0) return null
        val key = (target shl 2) or lang.toLong()
        var low = 0
        var high = layout.localizedCount - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val at = layout.localized + mid * LOCALIZED_SIZE
            val found = u32(at)
            when {
                found < key -> low = mid + 1
                found > key -> high = mid - 1
                else -> return name(u32(at + 4))
            }
        }
        return null
    }

    private fun name(offset: Long): String {
        if (offset + 2 > layout.namesSize) throw BrokenPackException("name offset $offset out of range")
        val at = layout.names + offset.toInt()
        val length = u16(at)
        if (offset + 2 + length > layout.namesSize) throw BrokenPackException("name at $offset is cut off")
        val bytes = ByteArray(length)
        val view = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(at + 2)
        view.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun cellIndex(cell: Int): Int {
        val value = u32(layout.cellIndex + cell * 4)
        if (value > layout.entryCount) throw BrokenPackException("cell index $value beyond the entries")
        return value.toInt()
    }

    private fun entryLat(i: Int) = buf.getInt(layout.entries + i * ENTRY_SIZE) / SCALE

    private fun entryLon(i: Int) = buf.getInt(layout.entries + i * ENTRY_SIZE + 4) / SCALE

    private fun u16(at: Int) = buf.getShort(at).toInt() and 0xFFFF

    private fun u32(at: Int) = buf.getInt(at).toLong() and 0xFFFFFFFFL

    /** The header fields, checked against the file size. */
    private class Layout(
        val minPopulation: Int,
        val entryCount: Int,
        val admin1Count: Int,
        val localizedCount: Int,
        val languageCount: Int,
        val languages: Int,
        val cellIndex: Int,
        val entries: Int,
        val admin1: Int,
        val localized: Int,
        val names: Int,
        val namesSize: Int,
    )

    companion object {
        /** Nothing farther than this is a place of the photo. */
        const val MAX_KM = 50.0

        private const val HEADER_SIZE = 64
        private const val VERSION = 1
        private const val ROWS = 180
        private const val COLS = 360
        private const val ENTRY_SIZE = 16
        private const val ADMIN_SIZE = 8
        private const val LOCALIZED_SIZE = 8
        private const val NO_ADMIN = 0xFFFF
        private const val MAX_LANGUAGES = 64
        private const val SCALE = 100_000.0
        private const val MAX_LAT = 89.99999
        private const val POLE_LAT = 89.0
        private const val EARTH_KM = 6371.0088
        private const val KM_PER_DEGREE = EARTH_KM * PI / 180.0
        private const val SAFETY = 0.95
        private const val CELL_INDEX_BYTES = (ROWS * COLS + 1) * 4L
        private val MAGIC = "GLPL".toByteArray(Charsets.US_ASCII)

        /** Maps [file] read-only. Throws [BrokenPackException] when it is not a valid pack, [IOException] otherwise. */
        fun open(file: File): PlacePack {
            val mapped = FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            }
            return of(mapped)
        }

        /** A pack in [buffer], whose content starts at its position 0. */
        fun of(buffer: ByteBuffer): PlacePack {
            val buf = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            buf.position(0)
            val layout = parseLayout(buf, buf.limit().toLong())
            val last = buf.getInt(layout.cellIndex + ROWS * COLS * 4).toLong() and 0xFFFFFFFFL
            if (last != layout.entryCount.toLong()) throw BrokenPackException("Cell index does not end at the entries")
            return PlacePack(buf, layout)
        }

        /** Throws [BrokenPackException] unless [file] looks like a complete pack; reads only its header. */
        fun check(file: File) {
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                while (header.hasRemaining()) {
                    if (channel.read(header) < 0) break
                }
                header.flip()
                parseLayout(header, channel.size())
            }
        }

        @Suppress("CyclomaticComplexMethod", "ThrowsCount") // one check per header rule
        private fun parseLayout(header: ByteBuffer, size: Long): Layout {
            if (size < HEADER_SIZE || header.limit() < HEADER_SIZE) throw BrokenPackException("Pack is too short")
            for (i in MAGIC.indices) if (header.get(i) != MAGIC[i]) throw BrokenPackException("Bad magic")
            val version = header.getShort(4).toInt() and 0xFFFF
            if (version != VERSION) throw BrokenPackException("Unsupported version $version")

            fun u32(at: Int) = header.getInt(at).toLong() and 0xFFFFFFFFL
            val entryCount = u32(16)
            val admin1Count = u32(20)
            val localizedCount = u32(24)
            val languageCount = u32(28)
            val languages = u32(32)
            val cellIndex = u32(36)
            val entries = u32(40)
            val admin1 = u32(44)
            val localized = u32(48)
            val names = u32(52)
            val namesSize = u32(56)
            if (admin1Count > NO_ADMIN || languageCount > MAX_LANGUAGES || entryCount > Int.MAX_VALUE / ENTRY_SIZE) {
                throw BrokenPackException("Implausible counts")
            }
            val sections = listOf(
                languages to languageCount * 4,
                cellIndex to CELL_INDEX_BYTES,
                entries to entryCount * ENTRY_SIZE,
                admin1 to admin1Count * ADMIN_SIZE,
                localized to localizedCount * LOCALIZED_SIZE,
                names to namesSize,
            )
            for ((offset, length) in sections) {
                if (offset % 4 != 0L || offset < HEADER_SIZE || offset + length > size) {
                    throw BrokenPackException("A section of the pack does not fit the file")
                }
            }
            if (localizedCount * LOCALIZED_SIZE > Int.MAX_VALUE) throw BrokenPackException("Implausible counts")
            return Layout(
                minPopulation = u32(8).toInt(),
                entryCount = entryCount.toInt(),
                admin1Count = admin1Count.toInt(),
                localizedCount = localizedCount.toInt(),
                languageCount = languageCount.toInt(),
                languages = languages.toInt(),
                cellIndex = cellIndex.toInt(),
                entries = entries.toInt(),
                admin1 = admin1.toInt(),
                localized = localized.toInt(),
                names = names.toInt(),
                namesSize = namesSize.toInt(),
            )
        }

        /** Longitude wrapped into [-180, 180). */
        internal fun normalizeLon(lon: Double): Double {
            val wrapped = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            return if (wrapped >= 180.0) -180.0 else wrapped
        }

        internal fun haversineKm(
            lat1: Double,
            lon1: Double,
            lat2: Double,
            lon2: Double,
        ): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * EARTH_KM * asin(min(1.0, sqrt(a)))
        }
    }
}
