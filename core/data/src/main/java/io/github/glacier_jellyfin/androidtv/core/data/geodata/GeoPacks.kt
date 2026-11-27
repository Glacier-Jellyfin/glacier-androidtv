package io.github.glacier_jellyfin.androidtv.core.data.geodata

/**
 * One downloadable place pack: the file of [level] (minimum inhabitants) as an asset of the geodata release.
 * [level] 0 is the finest pack, "All places", without a population filter.
 * [sha256] is the lower-case hex digest of the `.gz` file, [size] its length in bytes, [rawSize] the length of the
 * unpacked `.bin` (needed to check the free storage before the download).
 */
data class GeoPack(
    val level: Int,
    val file: String,
    val sha256: String,
    val size: Long,
    val rawSize: Long,
)

/**
 * The pinned table of place packs. Replace [ALL] and [BASE_URL] when a new geodata release is published; nothing else
 * has to change. The packs are built by `scripts/geodata`.
 */
object GeoPacks {

    /** Directory of the geodata release; every [GeoPack.file] is an asset in it. */
    const val BASE_URL = "https://github.com/Glacier-Jellyfin/glacier-androidtv/releases/download/geodata-2026-11/"

    val ALL: List<GeoPack> = listOf(
        GeoPack(
            level = 15_000,
            file = "places-15000.bin.gz",
            sha256 = "45f860a6dca9ce8aa8b454e9a1917298a81ca90eae9a6c8ce415df58b4f12e19",
            size = 843_508L,
            rawSize = 1_814_971L,
        ),
        GeoPack(
            level = 5_000,
            file = "places-5000.bin.gz",
            sha256 = "69ee1d93bd219ce58498e42b55612bf7b395cc07a8e618fe74a2a252c9cd53b2",
            size = 1_244_359L,
            rawSize = 2_579_155L,
        ),
        GeoPack(
            level = 1_000,
            file = "places-1000.bin.gz",
            sha256 = "89540a9d677f040f87575ce8393856f135f3f731790cb4c191173e7b6e50d03c",
            size = 2_748_201L,
            rawSize = 5_500_930L,
        ),
        GeoPack(
            level = 500,
            file = "places-500.bin.gz",
            sha256 = "bab5f2fe3f440adc3461adb5b88c3e8daab990ea93939257327acc51498e1379",
            size = 3_334_046L,
            rawSize = 6_663_463L,
        ),
        GeoPack(
            level = 0,
            file = "places-all.bin.gz",
            sha256 = "53984b640463b18e2c9fe015f911463efd6c92e82f2adb97abd219b371fec22f",
            size = 56_843_057L,
            rawSize = 119_845_846L,
        ),
    )

    /** The pack that is an asset of the APK (see [AssetPackSource]): only unpacked on the device, never downloaded. */
    const val BUNDLED_LEVEL = 15_000

    /** The levels the setting offers, coarsest first. */
    val LEVELS: List<Int> get() = ALL.map { it.level }

    fun forLevel(level: Int): GeoPack? = ALL.firstOrNull { it.level == level }

    /**
     * Maps a stored level to one that has a pack. `null` (off) stays `null` and a level with a pack stays as it is.
     * A level without a pack, for example 300 from an older version, becomes the finest pack that is coarser than it:
     * the smallest non-zero level that is at least the stored one. If there is none, it is [BUNDLED_LEVEL].
     */
    fun normalize(level: Int?): Int? = when {
        level == null -> null
        forLevel(level) != null -> level
        else -> LEVELS.filter { it != 0 && it >= level }.minOrNull() ?: BUNDLED_LEVEL
    }
}
