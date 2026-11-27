package io.github.glacier_jellyfin.androidtv.core.data.geodata

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import kotlin.math.floor
import kotlin.math.roundToInt

/** A region of the test pack; [localized] maps a language code to its name. */
data class TestAdmin(
    val country: String,
    val name: String,
    val localized: Map<String, String> = emptyMap(),
)

data class TestPlace(
    val name: String,
    val lat: Double,
    val lon: Double,
    val country: String? = "DE",
    val admin1: TestAdmin? = null,
    val localized: Map<String, String> = emptyMap(),
)

/** Writes binary format v1 (see [PlacePack]) for tests. */
object TestPack {
    val LANGUAGES = listOf("en", "de", "es")

    private const val CELLS = 180 * 360

    fun build(places: List<TestPlace>, level: Int = 15_000): ByteArray {
        val sorted = places.sortedWith(compareBy({ cell(it.lat, it.lon) }, { micro(it.lat) }, { micro(it.lon) }))
        val admins = sorted.mapNotNull { it.admin1 }.distinct().sortedWith(compareBy({ it.country }, { it.name }))

        val names = ByteArrayOutputStream()
        val offsets = HashMap<String, Int>()
        fun name(text: String) = offsets.getOrPut(text) {
            val at = names.size()
            val bytes = text.toByteArray(Charsets.UTF_8)
            names.write(bytes.size and 0xFF)
            names.write(bytes.size shr 8)
            names.write(bytes)
            at
        }

        val localized = ArrayList<Pair<Long, Int>>()
        sorted.forEachIndexed { i, p ->
            p.localized.forEach { (lang, text) ->
                localized += ((i.toLong() shl 2) or index(lang).toLong()) to name(text)
            }
        }
        admins.forEachIndexed { a, admin ->
            admin.localized.forEach { (lang, text) ->
                localized += (((sorted.size + a).toLong() shl 2) or index(lang).toLong()) to name(text)
            }
        }
        localized.sortBy { it.first }

        val entryNames = sorted.map { name(it.name) }
        val adminNames = admins.map { name(it.name) }

        val cellIndex = IntArray(CELLS + 1)
        sorted.forEach { cellIndex[cell(it.lat, it.lon) + 1]++ }
        for (c in 1..CELLS) cellIndex[c] += cellIndex[c - 1]

        val languagesOffset = 64
        val cellOffset = languagesOffset + LANGUAGES.size * 4
        val entriesOffset = cellOffset + (CELLS + 1) * 4
        val adminOffset = entriesOffset + sorted.size * 16
        val localizedOffset = adminOffset + admins.size * 8
        val namesOffset = localizedOffset + localized.size * 8
        val nameBytes = names.toByteArray()
        val buf = ByteBuffer.allocate((namesOffset + nameBytes.size + 3) and 3.inv()).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("GLPL".toByteArray())
        buf.putShort(1).putShort(0).putInt(level).putInt(20_261_127)
        buf.putInt(sorted.size).putInt(admins.size).putInt(localized.size).putInt(LANGUAGES.size)
        buf.putInt(languagesOffset).putInt(cellOffset).putInt(entriesOffset).putInt(adminOffset)
        buf.putInt(localizedOffset).putInt(namesOffset).putInt(nameBytes.size).putInt(0)
        LANGUAGES.forEach { buf.put(it.toByteArray()).put(0).put(0) }
        cellIndex.forEach { buf.putInt(it) }
        sorted.forEachIndexed { i, p ->
            buf.putInt(micro(p.lat)).putInt(micro(p.lon))
            buf.put(code(p.country))
            val admin = p.admin1?.let { admins.indexOf(it) } ?: 0xFFFF
            buf.putShort(admin.toShort()).putInt(entryNames[i])
        }
        admins.forEachIndexed { a, admin -> buf.put(code(admin.country)).putShort(0).putInt(adminNames[a]) }
        localized.forEach { buf.putInt(it.first.toInt()).putInt(it.second) }
        buf.put(nameBytes)
        return buf.array()
    }

    fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(bytes) }
    }.toByteArray()

    private fun index(lang: String) = LANGUAGES.indexOf(lang).also { require(it >= 0) }

    private fun micro(degrees: Double) = (degrees * 100_000).roundToInt()

    private fun cell(lat: Double, lon: Double): Int = (floor(lat.coerceIn(-90.0, 89.99999)).toInt() + 90) * 360 +
        (floor(PlacePack.normalizeLon(lon)).toInt() + 180)

    private fun code(country: String?): ByteArray = (country ?: "\u0000\u0000").toByteArray(Charsets.ISO_8859_1)
}
